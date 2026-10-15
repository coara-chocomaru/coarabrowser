package com.coara.browser.util;

import android.content.Context;
import android.webkit.WebView;
import android.widget.Toast;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PageTranslator {

    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String ONLINE_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String ASSET_NAME = "page_translate.js";
    private static final int CHUNK_CHARS = 3000;
    private static final int CHUNK_SEGMENTS = 50;
    private static final long POLL_MS = 1000L;
    private static final long POLL_MAX_MS = 5000L;
    private static final long MODEL_HINT_DELAY_MS = 1500L;
    private static final String STATE_JS = "(function(){var s=window.__coaraTr;return s&&s.active?1:0;})()";
    private static final String POLL_JS = "(function(){var s=window.__coaraTr;return s?(s.dirty?1:0):-1;})()";
    private static final String RESTORE_JS = "(function(){var s=window.__coaraTr;if(s)s.restore();return 1;})()";

    private static final Map<WebView, Session> SESSIONS = new WeakHashMap<>();
    private static final Map<String, Translator> TRANSLATORS = new HashMap<>();
    private static String extractScript;
    private static ExecutorService netExecutor;
    private static LanguageIdentifier identifier;

    private PageTranslator() {
    }

    private static final class Chunk {
        final int base;
        final String[] texts;

        Chunk(int base, String[] texts) {
            this.base = base;
            this.texts = texts;
        }
    }

    private static final class Session {
        final WeakReference<WebView> view;
        final boolean allowOnline;
        final ArrayDeque<Chunk> mlQueue = new ArrayDeque<>();
        String source;
        boolean online;
        boolean started;
        boolean failed;
        boolean errorShown;
        boolean mlReady;
        boolean mlRunning;
        boolean polling;
        int pending;
        long pollMs = POLL_MS;

        Session(WebView view, boolean allowOnline) {
            this.view = new WeakReference<>(view);
            this.allowOnline = allowOnline;
            this.online = allowOnline;
        }
    }

    public static void toggle(WebView view, boolean allowOnline) {
        if (view == null) {
            return;
        }
        String url = view.getUrl();
        if (url == null || url.isEmpty()
                || !(url.startsWith("http://") || url.startsWith("https://"))
                || BrowserAssetLoader.isAppAssetUrl(url)) {
            toast(view, "このページは翻訳できません");
            return;
        }
        view.evaluateJavascript(STATE_JS, value -> {
            if ("1".equals(value)) {
                restore(view);
            } else {
                start(view, allowOnline);
            }
        });
    }

    public static void release() {
        for (Translator translator : TRANSLATORS.values()) {
            try {
                translator.close();
            } catch (Throwable ignored) {
            }
        }
        TRANSLATORS.clear();
        if (identifier != null) {
            try {
                identifier.close();
            } catch (Throwable ignored) {
            }
            identifier = null;
        }
        if (netExecutor != null) {
            netExecutor.shutdownNow();
            netExecutor = null;
        }
        SESSIONS.clear();
    }

    private static void start(WebView view, boolean allowOnline) {
        Session session = new Session(view, allowOnline);
        SESSIONS.put(view, session);
        toast(view, "翻訳中…");
        extract(session, true);
    }

    private static void restore(WebView view) {
        SESSIONS.remove(view);
        try {
            view.evaluateJavascript(RESTORE_JS, null);
        } catch (Exception ignored) {
        }
        toast(view, "元の表示に戻しました");
    }

    private static boolean alive(Session session) {
        WebView view = session.view.get();
        return view != null && SESSIONS.get(view) == session;
    }

    private static void extract(Session session, boolean first) {
        WebView view = session.view.get();
        if (view == null || !alive(session)) {
            return;
        }
        String script = script(view.getContext());
        if (script.isEmpty()) {
            abort(session, "翻訳できませんでした");
            return;
        }
        try {
            view.evaluateJavascript(script, value -> onExtracted(session, value, first));
        } catch (Exception e) {
            abort(session, "翻訳できませんでした");
        }
    }

    private static void onExtracted(Session session, String value, boolean first) {
        WebView view = session.view.get();
        if (view == null || !alive(session)) {
            return;
        }
        JSONObject result;
        try {
            result = new JSONObject(value);
        } catch (Exception e) {
            if (first) {
                abort(session, "翻訳できませんでした");
            } else {
                schedulePoll(session);
            }
            return;
        }
        JSONArray array = result.optJSONArray("texts");
        int count = array == null ? 0 : array.length();
        if (count == 0) {
            if (first) {
                abort(session, "翻訳できるテキストがありません");
            } else {
                session.pollMs = Math.min(POLL_MAX_MS, session.pollMs + 500L);
                schedulePoll(session);
            }
            return;
        }
        session.pollMs = POLL_MS;
        String[] texts = new String[count];
        for (int i = 0; i < count; i++) {
            texts[i] = array.optString(i);
        }
        int base = result.optInt("base", 0);
        String lang = result.optString("lang", "");
        if (session.source == null) {
            detect(session, texts, lang, base);
        } else {
            dispatch(session, base, texts);
        }
    }

    private static void detect(Session session, String[] texts, String htmlLang, int base) {
        StringBuilder sample = new StringBuilder();
        for (String text : texts) {
            if (sample.length() >= 800) {
                break;
            }
            sample.append(text).append(' ');
        }
        try {
            identifier().identifyLanguage(sample.toString())
                    .addOnSuccessListener(tag -> onSource(session, tag, htmlLang, base, texts))
                    .addOnFailureListener(e -> onSource(session, "und", htmlLang, base, texts));
        } catch (Throwable t) {
            onSource(session, "und", htmlLang, base, texts);
        }
    }

    private static void onSource(Session session, String tag, String htmlLang, int base, String[] texts) {
        WebView view = session.view.get();
        if (view == null || !alive(session)) {
            return;
        }
        String source = primary(tag);
        if (source.isEmpty() || "und".equals(source)) {
            source = primary(htmlLang);
        }
        if (source.isEmpty()) {
            source = "auto";
        }
        if ("ja".equals(source)) {
            abort(session, "このページは日本語です");
            return;
        }
        session.source = source;
        dispatch(session, base, texts);
    }

    private static void dispatch(Session session, int base, String[] texts) {
        session.started = true;
        int from = 0;
        while (from < texts.length) {
            int to = from;
            int chars = 0;
            while (to < texts.length && to - from < CHUNK_SEGMENTS && (to == from || chars + texts[to].length() <= CHUNK_CHARS)) {
                chars += texts[to].length();
                to++;
            }
            String[] part = new String[to - from];
            System.arraycopy(texts, from, part, 0, part.length);
            Chunk chunk = new Chunk(base + from, part);
            session.pending++;
            if (session.online) {
                runOnline(session, chunk);
            } else {
                mlEnqueue(session, chunk);
            }
            from = to;
        }
    }

    private static void runOnline(Session session, Chunk chunk) {
        final String source = session.source;
        executor().execute(() -> {
            String[] translated = null;
            try {
                translated = onlineTranslate(source, chunk.texts);
            } catch (Throwable ignored) {
            }
            final String[] result = translated;
            UiThread.mainHandler().post(() -> {
                if (!alive(session)) {
                    return;
                }
                if (result != null) {
                    apply(session, chunk, result);
                    finish(session);
                } else {
                    session.online = false;
                    mlEnqueue(session, chunk);
                }
            });
        });
    }

    private static String[] onlineTranslate(String source, String[] texts) throws Exception {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(texts[i]);
        }
        String output = post(source, joined.toString());
        String[] parts = output.split("\n", -1);
        int end = parts.length;
        while (end > 0 && parts[end - 1].isEmpty()) {
            end--;
        }
        if (end == texts.length) {
            String[] result = new String[end];
            System.arraycopy(parts, 0, result, 0, end);
            return result;
        }
        String[] result = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            String single = post(source, texts[i]);
            result[i] = single.replace("\n", " ").trim();
        }
        return result;
    }

    private static String post(String source, String text) throws Exception {
        String body = "client=gtx&sl=" + URLEncoder.encode(source, "UTF-8")
                + "&tl=ja&dt=t&dj=1&ie=UTF-8&oe=UTF-8&q=" + URLEncoder.encode(text, "UTF-8");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", ONLINE_UA);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(payload.length);
            connection.getOutputStream().write(payload);
            if (connection.getResponseCode() != 200) {
                throw new java.io.IOException("http " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                JSONObject json = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                JSONArray sentences = json.getJSONArray("sentences");
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < sentences.length(); i++) {
                    result.append(sentences.getJSONObject(i).optString("trans", ""));
                }
                return result.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void mlEnqueue(Session session, Chunk chunk) {
        session.mlQueue.add(chunk);
        mlPump(session);
    }

    private static void mlPump(Session session) {
        if (session.mlRunning || session.failed) {
            return;
        }
        Chunk chunk = session.mlQueue.poll();
        if (chunk == null) {
            return;
        }
        session.mlRunning = true;
        if (!alive(session)) {
            session.mlRunning = false;
            return;
        }
        Translator translator = translator(session.source);
        if (translator == null) {
            mlFail(session, "翻訳に失敗しました。ネットワーク接続を確認してください");
            return;
        }
        ensureModel(session, translator, () -> mlTranslate(session, translator, chunk, new String[chunk.texts.length], 0));
    }

    private static void ensureModel(Session session, Translator translator, Runnable ready) {
        if (session.mlReady) {
            ready.run();
            return;
        }
        final Runnable hint = () -> {
            WebView view = session.view.get();
            if (view != null && alive(session)) {
                toast(view, "翻訳モデルを準備中です(初回のみダウンロードします)");
            }
        };
        UiThread.mainHandler().postDelayed(hint, MODEL_HINT_DELAY_MS);
        try {
            translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                    .addOnSuccessListener(unused -> {
                        UiThread.mainHandler().removeCallbacks(hint);
                        session.mlReady = true;
                        if (alive(session)) {
                            ready.run();
                        } else {
                            session.mlRunning = false;
                        }
                    })
                    .addOnFailureListener(e -> {
                        UiThread.mainHandler().removeCallbacks(hint);
                        mlFail(session, "翻訳モデルを取得できませんでした。ネットワーク接続を確認してください");
                    });
        } catch (Throwable t) {
            UiThread.mainHandler().removeCallbacks(hint);
            mlFail(session, "翻訳に失敗しました");
        }
    }

    private static void mlTranslate(Session session, Translator translator, Chunk chunk, String[] result, int index) {
        if (!alive(session)) {
            session.mlRunning = false;
            return;
        }
        if (index >= result.length) {
            apply(session, chunk, result);
            session.mlRunning = false;
            finish(session);
            mlPump(session);
            return;
        }
        try {
            translator.translate(chunk.texts[index])
                    .addOnSuccessListener(text -> {
                        result[index] = text;
                        mlTranslate(session, translator, chunk, result, index + 1);
                    })
                    .addOnFailureListener(e -> {
                        result[index] = "";
                        mlTranslate(session, translator, chunk, result, index + 1);
                    });
        } catch (Throwable t) {
            result[index] = "";
            mlTranslate(session, translator, chunk, result, index + 1);
        }
    }

    private static void mlFail(Session session, String message) {
        session.mlRunning = false;
        session.failed = true;
        session.pending = 0;
        session.mlQueue.clear();
        WebView view = session.view.get();
        if (view != null && alive(session) && !session.errorShown) {
            session.errorShown = true;
            toast(view, message);
        }
    }

    private static void apply(Session session, Chunk chunk, String[] result) {
        WebView view = session.view.get();
        if (view == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (String item : result) {
            array.put(item == null ? "" : item);
        }
        try {
            view.evaluateJavascript("(function(){var s=window.__coaraTr;if(s)s.apply("
                    + chunk.base + "," + array.toString() + ");})()", null);
        } catch (Exception ignored) {
        }
    }

    private static void finish(Session session) {
        session.pending--;
        if (session.pending <= 0) {
            session.pending = 0;
            schedulePoll(session);
        }
    }

    private static void abort(Session session, String message) {
        WebView view = session.view.get();
        if (view == null) {
            return;
        }
        SESSIONS.remove(view);
        try {
            view.evaluateJavascript(RESTORE_JS, null);
        } catch (Exception ignored) {
        }
        toast(view, message);
    }

    private static void schedulePoll(Session session) {
        if (session.polling || session.failed || !alive(session)) {
            return;
        }
        session.polling = true;
        UiThread.mainHandler().postDelayed(() -> {
            session.polling = false;
            poll(session);
        }, session.pollMs);
    }

    private static void poll(Session session) {
        WebView view = session.view.get();
        if (view == null || !alive(session)) {
            return;
        }
        if (!view.isAttachedToWindow() || session.pending > 0) {
            schedulePoll(session);
            return;
        }
        try {
            view.evaluateJavascript(POLL_JS, value -> {
                if (!alive(session)) {
                    return;
                }
                if ("1".equals(value)) {
                    extract(session, false);
                } else if ("0".equals(value)) {
                    session.pollMs = Math.min(POLL_MAX_MS, session.pollMs + 500L);
                    schedulePoll(session);
                } else {
                    SESSIONS.remove(view);
                }
            });
        } catch (Exception e) {
            SESSIONS.remove(view);
        }
    }

    private static Translator translator(String tag) {
        String language = TranslateLanguage.fromLanguageTag(tag);
        if (language == null) {
            return null;
        }
        Translator translator = TRANSLATORS.get(language);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(language)
                    .setTargetLanguage(TranslateLanguage.JAPANESE)
                    .build());
            TRANSLATORS.put(language, translator);
        }
        return translator;
    }

    private static LanguageIdentifier identifier() {
        if (identifier == null) {
            identifier = LanguageIdentification.getClient(
                    new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build());
        }
        return identifier;
    }

    private static ExecutorService executor() {
        if (netExecutor == null) {
            netExecutor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "coara-translate");
                thread.setDaemon(true);
                return thread;
            });
        }
        return netExecutor;
    }

    private static String script(Context context) {
        if (extractScript == null) {
            try (InputStream in = context.getAssets().open(ASSET_NAME)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                extractScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
        return extractScript;
    }

    private static String primary(String tag) {
        if (tag == null) {
            return "";
        }
        String lower = tag.trim().toLowerCase(Locale.ROOT);
        int cut = lower.indexOf('-');
        if (cut < 0) {
            cut = lower.indexOf('_');
        }
        return cut > 0 ? lower.substring(0, cut) : lower;
    }

    private static void toast(WebView view, String message) {
        try {
            Toast.makeText(view.getContext().getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
