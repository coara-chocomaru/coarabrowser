package com.coara.browser.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class TextFileReader {
    private static final int DEFAULT_MAX_CHARS = 1024 * 1024;

    private TextFileReader() {}

    public static String readAll(InputStream inputStream) throws IOException {
        return readAll(inputStream, DEFAULT_MAX_CHARS);
    }

    public static String readAll(InputStream inputStream, int maxChars) throws IOException {
        if (inputStream == null) {
            throw new IOException("入力ストリームを開けません");
        }
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars");
        }
        StringBuilder builder = new StringBuilder(Math.min(maxChars, 8192));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (builder.length() > maxChars - count) {
                    throw new IOException("入力データが上限を超えています");
                }
                builder.append(buffer, 0, count);
            }
        }
        return builder.toString();
    }
}
