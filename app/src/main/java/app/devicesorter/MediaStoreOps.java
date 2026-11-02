package app.devicesorter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Copies a MediaStore item into DCIM/Camera without SAF.
 *
 * The destination is written as a pending MediaStore row, synced, read back and
 * compared with the source, and only then published. A failed copy deletes the
 * pending row, leaving the source untouched.
 */
final class MediaStoreOps {
    private MediaStoreOps() {}

    record Copy(Uri uri, String name, String hash, boolean existing) {}

    static Copy copyToCamera(Context context, MediaItem item) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            throw new IOException("Android 10 以下无法直接写入系统媒体库，请用目录授权复制");
        }
        ContentResolver resolver = context.getContentResolver();
        Uri source = sourceUri(item);
        if (source == null) throw new IOException("文件没有媒体库 URI，无法复制回 DCIM/Camera");
        long expectedSize = item.size;
        MediaDiscovery.State before = MediaDiscovery.stat(context, source);
        if (expectedSize >= 0 && before.size() != expectedSize) {
            throw new IOException("扫描后源文件大小已变化，请重新查找");
        }
        Uri collection = item.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String relative = MediaPaths.CAMERA_PATH + "/";
        String name = safeName(item.name);
        Existing existing = find(resolver, collection, relative, name);
        String sourceHash = null;
        if (existing != null && existing.size == before.size()) {
            sourceHash = Hashing.of(resolver, source);
            try {
                if (sourceHash.equals(Hashing.of(resolver, existing.uri))) {
                    return new Copy(existing.uri, name, sourceHash, true);
                }
            } catch (IOException ignored) {
                // An unreadable existing file must not block a fresh copy.
            }
        }
        name = freeName(resolver, collection, relative, name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                item.mime == null || item.mime.isEmpty() ? (item.video ? "video/mp4" : "image/jpeg") : item.mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri target = resolver.insert(collection, values);
        if (target == null) throw new IOException("无法在 DCIM/Camera 建立目标文件");
        boolean published = false;
        try {
            MessageDigest digest = sha256();
            long count = 0;
            try (InputStream in = resolver.openInputStream(source)) {
                if (in == null) throw new IOException("无法读取源文件");
                try (OutputStream out = resolver.openOutputStream(target, "w")) {
                    if (out == null) throw new IOException("无法写入 DCIM/Camera");
                    byte[] buffer = new byte[256 * 1024];
                    int length;
                    while ((length = in.read(buffer)) != -1) {
                        out.write(buffer, 0, length);
                        digest.update(buffer, 0, length);
                        count += length;
                    }
                    out.flush();
                }
            }
            if (before.size() >= 0 && count != before.size()) throw new IOException("复制字节数不一致");
            byte[] copied = digest.digest();
            String hash = Hashing.hex(copied);
            sync(resolver, target);
            String targetHash = Hashing.of(resolver, target);
            if (!Arrays.equals(copied, hexBytes(targetHash))) {
                throw new IOException("目标 SHA-256 校验失败");
            }
            MediaDiscovery.State after = MediaDiscovery.stat(context, source);
            if (after.size() != before.size() || after.modified() != before.modified()) {
                throw new IOException("复制期间源文件发生变化，保留原文件");
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(target, publish, null, null) < 1) {
                throw new IOException("无法发布 DCIM/Camera 目标文件");
            }
            published = true;
            return new Copy(target, actualName(resolver, target, name), hash, false);
        } finally {
            if (!published) {
                try { resolver.delete(target, null, null); } catch (RuntimeException ignored) { }
            }
        }
    }

    static Uri sourceUri(MediaItem item) {
        if (item.mediaUri != null && !item.mediaUri.isEmpty()) return Uri.parse(item.mediaUri);
        if (item.uri != null && "media".equals(Uri.parse(item.uri).getAuthority())) return Uri.parse(item.uri);
        return null;
    }

    private record Existing(Uri uri, long size) {}

    private static Existing find(ContentResolver resolver, Uri collection, String relative, String name) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = resolver.query(collection, projection, selection,
                new String[] {relative, name}, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            Uri uri = Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(0)));
            return new Existing(uri, cursor.isNull(1) ? -1 : cursor.getLong(1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String freeName(ContentResolver resolver, Uri collection, String relative, String original) {
        String name = original;
        for (int index = 1; index < 1000 && find(resolver, collection, relative, name) != null; index++) {
            name = Naming.collisionName(original, index);
        }
        return name;
    }

    private static String actualName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[] {MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    private static String safeName(String name) {
        String clean = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        return clean.isEmpty() ? "media" : clean;
    }

    private static void sync(ContentResolver resolver, Uri uri) throws IOException {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
            if (descriptor == null) throw new IOException("无法同步目标文件");
            descriptor.getFileDescriptor().sync();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] hexBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}