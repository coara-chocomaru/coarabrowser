package com.coara.browser.util;

import android.content.Context;
import android.webkit.WebView;
import android.widget.Toast;

import com.google.mlkit.common.MlKit;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PageTranslator {

    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final String ONLINE_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String ASSET_NAME = "page_translate.js";
    private static final int CHUNK_CHARS = 3000;
    private static final int CHUNK_SEGMENTS = 50;
    private static final int MAX_TRANSLATION_RESPONSE_BYTES = 2 * 1024 * 1024;
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
    private static boolean mlKitReady;

    private PageTranslator() {
    }

    private static final class Chunk {
        final int base;
        final String[] texts;
        final String source;

        Chunk(int base, String[] texts, String source) {
            this.base = base;
            this.texts = texts;
            this.source = source;
        }
    }

    private static final class BlockContext {
        final String text;
        final String lang;

        BlockContext(String text, String lang) {
            this.text = text == null ? "" : text;
            this.lang = lang == null ? "" : lang;
        }
    }

    private static final class Session {
        final WeakReference<WebView> view;
        final boolean allowOnline;
        final boolean silent;
        final String target;
        final ArrayDeque<Chunk> mlQueue = new ArrayDeque<>();
        String source;
        boolean online;
        boolean started;
        boolean failed;
        boolean errorShown;
        final boolean automatic;
        final Set<String> mlReadySources = new HashSet<>();
        boolean mlRunning;
        boolean polling;
        int pending;
        long pollMs = POLL_MS;

        Session(WebView view, boolean allowOnline, boolean silent, String target, String fixedSource) {
            this.view = new WeakReference<>(view);
            this.allowOnline = allowOnline;
            this.online = allowOnline;
            this.silent = silent;
            this.target = target;
            this.automatic = TranslateSettings.AUTO.equals(fixedSource);
            this.source = automatic ? null : fixedSource;
        }
    }

    private static boolean translatable(String url) {
        return url != null && !url.isEmpty()
                && (url.startsWith("http://") || url.startsWith("https://"))
                && !BrowserAssetLoader.isAppAssetUrl(url);
    }

    public static void toggle(WebView view, boolean allowOnline) {
        if (view == null) {
            return;
        }
        if (!translatable(view.getUrl())) {
            toast(view, "このページは翻訳できません");
            return;
        }
        ensureMlKit(view.getContext());
        view.evaluateJavascript(STATE_JS, value -> {
            if ("1".equals(value)) {
                restore(view);
            } else {
                start(view, allowOnline, false);
            }
        });
    }

    public static void onPageStarted(WebView view) {
        if (view != null) {
            SESSIONS.remove(view);
        }
    }

    public static void onPageFinished(WebView view, boolean allowOnline) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        if (!TranslateSettings.isAutoTranslate(context, !allowOnline)) {
            return;
        }
        if (!translatable(view.getUrl()) || SESSIONS.get(view) != null) {
            return;
        }
        ensureMlKit(context);
        start(view, allowOnline, true);
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

    private static void ensureMlKit(Context context) {
        if (mlKitReady) {
            return;
        }
        try {
            MlKit.initialize(context.getApplicationContext());
            mlKitReady = true;
        } catch (Throwable ignored) {
        }
    }

    private static void start(WebView view, boolean allowOnline, boolean silent) {
        Context context = view.getContext();
        boolean secret = !allowOnline;
        Session session = new Session(view, allowOnline, silent,
                TranslateSettings.getTarget(context, secret),
                TranslateSettings.getSource(context, secret));
        SESSIONS.put(view, session);
        if (!silent) {
            toast(view, "翻訳中…");
        }
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
            view.evaluateJavascript("window.__coaraTrTarget=" + JSONObject.quote(session.target == null ? "" : session.target) + ";" + script,
                    value -> onExtracted(session, value, first));
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
            if (first && !session.automatic) {
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
        JSONArray groupIdsJson = result.optJSONArray("groupIds");
        int[] groupIds = new int[count];
        Arrays.fill(groupIds, -1);
        if (groupIdsJson != null) {
            for (int i = 0; i < Math.min(count, groupIdsJson.length()); i++) {
                groupIds[i] = groupIdsJson.optInt(i, -1);
            }
        }
        List<BlockContext> groups = new ArrayList<>();
        JSONArray groupsJson = result.optJSONArray("groups");
        if (groupsJson != null) {
            for (int i = 0; i < groupsJson.length(); i++) {
                JSONObject group = groupsJson.optJSONObject(i);
                if (group != null) {
                    groups.add(new BlockContext(group.optString("text", ""), group.optString("lang", "")));
                }
            }
        }
        String lang = result.optString("lang", "");
        if (!session.automatic && session.source != null && session.source.equals(primary(session.target))) {
            abort(session, "翻訳元と翻訳先が同じ言語です");
            return;
        }
        if (session.automatic && session.source == null) {
            detect(session, texts, lang, result.optString("contentLang", ""), result.optString("sample", ""), base, groupIds, groups);
        } else if (session.automatic) {
            detectBlocks(session, base, texts, groupIds, groups, session.source);
        } else {
            dispatch(session, base, texts);
        }
    }

    private static void detect(Session session, String[] texts, String htmlLang, String contentLang, String sample, int base, int[] groupIds, List<BlockContext> groups) {
        String detectionText = sample == null ? "" : sample.trim();
        if (detectionText.isEmpty()) {
            StringBuilder fallback = new StringBuilder();
            for (String text : texts) {
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                if (fallback.length() > 0) {
                    fallback.append(' ');
                }
                int remaining = 1200 - fallback.length();
                if (remaining <= 0) {
                    break;
                }
                String clean = text.trim();
                fallback.append(clean, 0, Math.min(clean.length(), remaining));
            }
            detectionText = fallback.toString();
        }
        if (detectionText.length() > 1200) {
            detectionText = detectionText.substring(0, 1200);
        }
        final String textSample = detectionText;
        try {
            identifier().identifyLanguage(textSample)
                    .addOnSuccessListener(tag -> onSource(session, tag, htmlLang, contentLang, textSample, base, texts, groupIds, groups))
                    .addOnFailureListener(e -> onSource(session, "und", htmlLang, contentLang, textSample, base, texts, groupIds, groups));
        } catch (Throwable t) {
            onSource(session, "und", htmlLang, contentLang, textSample, base, texts, groupIds, groups);
        }
    }

    private static void onSource(Session session, String tag, String htmlLang, String contentLang, String sample, int base, String[] texts, int[] groupIds, List<BlockContext> groups) {
        WebView view = session.view.get();
        if (view == null || !alive(session)) {
            return;
        }
        String source = primary(tag);
        String target = primary(session.target);
        String inferred = inferScriptLanguage(sample, target);
        String contentHint = primary(contentLang);
        String pageHint = primary(htmlLang);
        boolean contentHintValid = !contentHint.isEmpty() && !"und".equals(contentHint) && !contentHint.equals(target);
        boolean pageHintValid = !pageHint.isEmpty() && !"und".equals(pageHint) && !pageHint.equals(target);
        if (source.isEmpty() || "und".equals(source)) {
            if (!inferred.isEmpty()) {
                if ("en".equals(inferred) && contentHintValid) {
                    source = contentHint;
                } else if ("en".equals(inferred) && pageHintValid) {
                    source = pageHint;
                } else if ("en".equals(inferred) && session.online) {
                    source = "auto";
                } else {
                    source = inferred;
                }
            } else if (contentHintValid) {
                source = contentHint;
            } else if (pageHintValid) {
                source = pageHint;
            } else if (session.online) {
                source = "auto";
            } else {
                source = target;
            }
        } else if (source.equals(target) || (!inferred.isEmpty() && inferred.equals(target))) {
            if (!inferred.isEmpty() && inferred.equals(target)) {
                source = inferred;
            } else if (!inferred.isEmpty() && "en".equals(inferred) && contentHintValid) {
                source = contentHint;
            } else if (!inferred.isEmpty() && "en".equals(inferred) && pageHintValid) {
                source = pageHint;
            } else if (!inferred.isEmpty() && "en".equals(inferred) && session.online) {
                source = "auto";
            } else if (!inferred.isEmpty()) {
                source = inferred;
            } else if (contentHintValid) {
                source = contentHint;
            } else if (pageHintValid) {
                source = pageHint;
            }
        }
        if (source.isEmpty() || "und".equals(source)) {
            source = session.online ? "auto" : target;
        }
        session.source = source;
        detectBlocks(session, base, texts, groupIds, groups, source);
    }

    private static void detectBlocks(Session session, int base, String[] texts, int[] groupIds, List<BlockContext> groups, String fallbackSource) {
        if (!alive(session)) {
            return;
        }
        String target = primary(session.target);
        String fallback = primary(fallbackSource);
        if (fallback.isEmpty() || "und".equals(fallback)) {
            fallback = session.online ? "auto" : target;
        }
        final String resolvedFallback = fallback;
        String[] groupSources = new String[groups == null ? 0 : groups.size()];
        List<Integer> identify = new ArrayList<>();
        for (int i = 0; i < groupSources.length; i++) {
            BlockContext group = groups.get(i);
            String hint = primary(group.lang);
            if (!hint.isEmpty() && !"und".equals(hint)) {
                groupSources[i] = hint;
                continue;
            }
            String scriptLanguage = inferScriptLanguage(group.text, target);
            if (scriptLanguage.equals(target)) {
                groupSources[i] = target;
            } else if (!scriptLanguage.isEmpty() && !"en".equals(scriptLanguage) && !("zh".equals(scriptLanguage) && "ja".equals(target))) {
                groupSources[i] = scriptLanguage;
            } else if (group.text.trim().length() >= 8 && (fallback.equals(target) || "auto".equals(fallback) || !"en".equals(fallback))) {
                identify.add(i);
            } else {
                groupSources[i] = fallback;
            }
        }
        if (identify.isEmpty()) {
            dispatchDetectedSources(session, base, texts, groupIds, groupSources, resolvedFallback);
            return;
        }
        int[] remaining = {identify.size()};
        boolean[] completed = {false};
        Runnable finishDetection = () -> {
            if (!completed[0] && remaining[0] == 0 && alive(session)) {
                completed[0] = true;
                dispatchDetectedSources(session, base, texts, groupIds, groupSources, resolvedFallback);
            }
        };
        LanguageIdentifier languageIdentifier;
        try {
            languageIdentifier = identifier();
        } catch (Throwable t) {
            for (Integer index : identify) {
                groupSources[index] = resolvedFallback;
                remaining[0]--;
            }
            finishDetection.run();
            return;
        }
        for (Integer index : identify) {
            final int groupIndex = index;
            String sample = groups.get(groupIndex).text;
            try {
                languageIdentifier.identifyLanguage(sample)
                        .addOnSuccessListener(tag -> {
                            String language = primary(tag);
                            if (language.isEmpty() || "und".equals(language)) {
                                language = inferScriptLanguage(sample, target);
                            }
                            if (language.isEmpty() || "und".equals(language)) {
                                language = resolvedFallback;
                            }
                            groupSources[groupIndex] = language;
                            remaining[0]--;
                            finishDetection.run();
                        })
                        .addOnFailureListener(e -> {
                            groupSources[groupIndex] = resolvedFallback;
                            remaining[0]--;
                            finishDetection.run();
                        });
            } catch (Throwable t) {
                groupSources[groupIndex] = resolvedFallback;
                remaining[0]--;
                finishDetection.run();
            }
        }
    }

    private static void dispatchDetectedSources(Session session, int base, String[] texts, int[] groupIds, String[] groupSources, String fallback) {
        String[] sources = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            int group = i < groupIds.length ? groupIds[i] : -1;
            String source = group >= 0 && group < groupSources.length ? groupSources[group] : fallback;
            if (source == null || source.isEmpty() || "und".equals(source)) {
                source = fallback;
            }
            sources[i] = source;
        }
        dispatchSources(session, base, texts, sources);
    }

    private static String inferScriptLanguage(String sample, String target) {
        if (sample == null || sample.isEmpty()) {
            return "";
        }
        int latin = 0;
        int kana = 0;
        int han = 0;
        int hangul = 0;
        int cyrillic = 0;
        int arabic = 0;
        int devanagari = 0;
        int thai = 0;
        int ukraine = 0;
        int letters = 0;
        for (int i = 0; i < sample.length();) {
            int cp = sample.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0x00C0 && cp <= 0x024F) || (cp >= 0x1E00 && cp <= 0x1EFF)) {
                latin++;
            } else if ((cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x31F0 && cp <= 0x31FF)) {
                kana++;
            } else if ((cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)) {
                han++;
            } else if (cp >= 0xAC00 && cp <= 0xD7AF) {
                hangul++;
            } else if ((cp >= 0x0400 && cp <= 0x052F) || (cp >= 0x2DE0 && cp <= 0x2DFF) || (cp >= 0xA640 && cp <= 0xA69F)) {
                cyrillic++;
                if (cp == 0x0406 || cp == 0x0456 || cp == 0x0407 || cp == 0x0457 || cp == 0x0490 || cp == 0x0491 || cp == 0x0454 || cp == 0x0404) {
                    ukraine++;
                }
            } else if ((cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F) || (cp >= 0x08A0 && cp <= 0x08FF)) {
                arabic++;
            } else if ((cp >= 0x0900 && cp <= 0x097F) || (cp >= 0xA8E0 && cp <= 0xA8FF)) {
                devanagari++;
            } else if (cp >= 0x0E00 && cp <= 0x0E7F) {
                thai++;
            }
        }
        if (letters < 8) {
            return "";
        }
        int nonLatin = kana + han + hangul + cyrillic + arabic + devanagari + thai;
        if (kana >= 4 && kana * 5 >= Math.max(1, latin) && kana + han >= latin / 3) {
            return "ja";
        }
        if (hangul >= 8 && hangul * 2 >= Math.max(1, latin)) {
            return "ko";
        }
        if (thai >= 8 && thai * 2 >= Math.max(1, latin)) {
            return "th";
        }
        if (arabic >= 8 && arabic * 2 >= Math.max(1, latin)) {
            return "ar";
        }
        if (devanagari >= 8 && devanagari * 2 >= Math.max(1, latin)) {
            return "hi";
        }
        if (cyrillic >= 8 && cyrillic * 2 >= Math.max(1, latin)) {
            return ukraine * 2 >= cyrillic ? "uk" : "ru";
        }
        if (han >= 8 && kana == 0 && han * 2 >= Math.max(1, latin)) {
            return "zh";
        }
        if (latin >= 12 && nonLatin * 4 < latin && ("ja".equals(target) || "zh".equals(target) || "ko".equals(target) || "ru".equals(target) || "uk".equals(target) || "ar".equals(target) || "hi".equals(target) || "th".equals(target))) {
            return "en";
        }
        return "";
    }

    private static void dispatch(Session session, int base, String[] texts) {
        String[] sources = new String[texts.length];
        Arrays.fill(sources, session.source);
        dispatchSources(session, base, texts, sources);
    }

    private static void dispatchSources(Session session, int base, String[] texts, String[] sources) {
        session.started = true;
        String target = primary(session.target);
        int from = 0;
        while (from < texts.length) {
            String source = from < sources.length ? primary(sources[from]) : "";
            if (source.isEmpty() || "und".equals(source) || source.equals(target)) {
                from++;
                continue;
            }
            int to = from;
            int chars = 0;
            while (to < texts.length && to - from < CHUNK_SEGMENTS) {
                String nextSource = to < sources.length ? primary(sources[to]) : "";
                if (!source.equals(nextSource)) {
                    break;
                }
                int nextLength = texts[to] == null ? 0 : texts[to].length();
                if (to > from && chars + nextLength > CHUNK_CHARS) {
                    break;
                }
                chars += nextLength;
                to++;
            }
            if (to <= from) {
                from++;
                continue;
            }
            String[] part = new String[to - from];
            System.arraycopy(texts, from, part, 0, part.length);
            Chunk chunk = new Chunk(base + from, part, source);
            session.pending++;
            if (session.online) {
                runOnline(session, chunk);
            } else {
                mlEnqueue(session, chunk);
            }
            from = to;
        }
        if (session.pending == 0) {
            schedulePoll(session);
        }
    }

    private static void runOnline(Session session, Chunk chunk) {
        final String source = chunk.source;
        final String target = session.target;
        executor().execute(() -> {
            String[] translated = null;
            try {
                translated = onlineTranslate(source, target, chunk.texts);
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

    private static String[] onlineTranslate(String source, String target, String[] texts) throws Exception {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(texts[i]);
        }
        String output = post(source, target, joined.toString());
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
            String single = post(source, target, texts[i]);
            result[i] = single.replace("\n", " ").trim();
        }
        return result;
    }

    private static String onlineCode(String tag) {
        return "zh".equals(tag) ? "zh-CN" : tag;
    }

    private static String post(String source, String target, String text) throws Exception {
        String body = "client=gtx&sl=" + URLEncoder.encode(onlineCode(source), "UTF-8")
                + "&tl=" + URLEncoder.encode(onlineCode(target), "UTF-8")
                + "&dt=t&dj=1&ie=UTF-8&oe=UTF-8&q=" + URLEncoder.encode(text, "UTF-8");
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
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() > MAX_TRANSLATION_RESPONSE_BYTES - read) {
                        throw new java.io.IOException("translation response too large");
                    }
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
        Translator translator = translator(chunk.source, session.target);
        if (translator == null) {
            session.mlRunning = false;
            finish(session);
            mlPump(session);
            return;
        }
        ensureModel(session, translator, chunk.source, () -> mlTranslate(session, translator, chunk, new String[chunk.texts.length], 0));
    }

    private static void ensureModel(Session session, Translator translator, String source, Runnable ready) {
        String key = primary(source) + ">" + primary(session.target);
        if (session.mlReadySources.contains(key)) {
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
                        session.mlReadySources.add(key);
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
        if (view != null && alive(session) && !session.errorShown && !session.silent) {
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
        if (!session.silent && !message.isEmpty()) {
            toast(view, message);
        }
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

    private static Translator translator(String sourceTag, String targetTag) {
        String from = TranslateLanguage.fromLanguageTag(sourceTag);
        String to = TranslateLanguage.fromLanguageTag(targetTag);
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        String key = from + ">" + to;
        Translator translator = TRANSLATORS.get(key);
        if (translator == null) {
            translator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(from)
                    .setTargetLanguage(to)
                    .build());
            TRANSLATORS.put(key, translator);
        }
        return translator;
    }

    private static LanguageIdentifier identifier() {
        if (identifier == null) {
            identifier = LanguageIdentification.getClient(
                    new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.65f).build());
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
