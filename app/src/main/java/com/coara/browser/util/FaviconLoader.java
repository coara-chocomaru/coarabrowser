package com.coara.browser.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class FaviconLoader {
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 192;

    private FaviconLoader() {
    }

    public static Bitmap fetch(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            URL page = new URL(pageUrl);
            String protocol = page.getProtocol();
            String host = page.getHost();
            if (host == null || host.isEmpty()
                    || !("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))) {
                return null;
            }
            URL faviconUrl = new URL(protocol + "://" + host + "/favicon.ico");
            connection = (HttpURLConnection) faviconUrl.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                return null;
            }
            int initialCapacity = contentLength > 0 ? Math.min(contentLength, 32768) : 8192;
            byte[] bytes;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    if (total > MAX_DOWNLOAD_BYTES - read) {
                        return null;
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                bytes = output.toByteArray();
            }
            if (bytes.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sampleSize = 1;
            while (Math.max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_DIMENSION
                    && sampleSize < (1 << 30)) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
