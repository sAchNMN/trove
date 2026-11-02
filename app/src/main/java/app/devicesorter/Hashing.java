package app.devicesorter;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashing {
    private Hashing() {}

    static String of(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取文件内容");
            return of(in);
        } catch (RuntimeException e) {
            throw new IOException("无法读取文件内容：" + e.getMessage(), e);
        }
    }

    static String of(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        byte[] buffer = new byte[256 * 1024];
        int length;
        while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}