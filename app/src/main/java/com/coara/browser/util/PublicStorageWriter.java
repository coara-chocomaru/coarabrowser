package com.coara.browser.util;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class PublicStorageWriter {

    public static final class Target {
        public final OutputStream outputStream;
        public final Uri mediaUri;
        public final File legacyFile;
        public final String displayPath;
        public final String localUri;

        Target(OutputStream outputStream, Uri mediaUri, File legacyFile, String displayPath, String localUri) {
            this.outputStream = outputStream;
            this.mediaUri = mediaUri;
            this.legacyFile = legacyFile;
            this.displayPath = displayPath;
            this.localUri = localUri;
        }
    }

    private PublicStorageWriter() {
    }

    public static Target openDownloadsTarget(Context context, String fileName, String mimeType) throws IOException {
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : null;
        return open(context, fileName, mimeType, Environment.DIRECTORY_DOWNLOADS, collection);
    }

    public static Target openPicturesTarget(Context context, String fileName, String mimeType) throws IOException {
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : null;
        return open(context, fileName, mimeType, Environment.DIRECTORY_PICTURES, collection);
    }

    private static Target open(Context context, String fileName, String mimeType, String publicDir, Uri collection) throws IOException {
        String safeFileName = normalizeFileName(fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && collection != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName);
            if (mimeType != null && !mimeType.isEmpty()) {
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            }
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                throw new IOException("insert failed");
            }
            try {
                OutputStream out = context.getContentResolver().openOutputStream(uri);
                if (out == null) {
                    context.getContentResolver().delete(uri, null, null);
                    throw new IOException("openOutputStream failed");
                }
                return new Target(out, uri, null, publicDir + "/" + safeFileName, uri.toString());
            } catch (Exception e) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException("openOutputStream failed", e);
            }
        }
        File dir = Environment.getExternalStoragePublicDirectory(publicDir);
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("mkdir failed");
        }
        File canonicalDir = dir.getCanonicalFile();
        if (!canonicalDir.isDirectory()) {
            throw new IOException("invalid output directory");
        }
        File candidate = new File(canonicalDir, safeFileName);
        File file = candidate.getCanonicalFile();
        if (!file.equals(candidate.getAbsoluteFile()) || file.getParentFile() == null
                || !file.getParentFile().equals(canonicalDir) || file.isDirectory()) {
            throw new IOException("invalid output path");
        }
        OutputStream out = new FileOutputStream(file);
        return new Target(out, null, file, file.getAbsolutePath(), Uri.fromFile(file).toString());
    }

    private static String normalizeFileName(String fileName) throws IOException {
        String candidate = fileName;
        if (candidate != null && candidate.length() > 1024) {
            candidate = candidate.substring(0, 1024);
        }
        String safe = DownloadSupport.sanitizeFileName(candidate);
        if (safe == null || safe.trim().isEmpty()) {
            safe = "download";
        }
        if (safe.length() > 180) {
            int end = 180;
            if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) {
                end--;
            }
            safe = safe.substring(0, end);
        }
        while (!safe.isEmpty() && (safe.endsWith(".") || safe.endsWith(" "))) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)
                || safe.indexOf('/') >= 0 || safe.indexOf('\\') >= 0 || new File(safe).isAbsolute()) {
            throw new IOException("invalid file name");
        }
        return safe;
    }

    public static boolean finish(Context context, Target target) {
        if (target == null) {
            return false;
        }
        try {
            target.outputStream.close();
        } catch (IOException e) {
            return false;
        }
        if (target.mediaUri != null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try {
                int updated = context.getContentResolver().update(target.mediaUri, values, null, null);
                return updated > 0;
            } catch (Exception ignored) {
                return false;
            }
        } else if (target.legacyFile != null) {
            try {
                MediaScannerConnection.scanFile(context,
                        new String[]{target.legacyFile.getAbsolutePath()}, null, null);
            } catch (Exception ignored) {
            }
            return target.legacyFile.exists();
        }
        return false;
    }

    public static void abort(Context context, Target target) {
        if (target == null) {
            return;
        }
        try {
            target.outputStream.close();
        } catch (IOException ignored) {
        }
        if (target.mediaUri != null) {
            try {
                context.getContentResolver().delete(target.mediaUri, null, null);
            } catch (Exception ignored) {
            }
        } else if (target.legacyFile != null) {
            try {
                target.legacyFile.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
