package app.devicesorter;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Requests a provider move only; never copies, deletes or retries an ambiguous result. */
final class NativeMove {
    interface Files {
        InputStream readSource() throws IOException;
        InputStream readTarget() throws IOException;
        long sourceSize() throws IOException;
        long sourceModified() throws IOException;
        boolean supported() throws IOException;
        boolean targetExists() throws IOException;
        void move() throws IOException;
        void record(String status, String hash) throws IOException;
        boolean cancelled();
    }

    static final class UncertainException extends IOException {
        UncertainException(Exception cause) {
            super("系统移动可能已发生，请检查原路径、目标路径及日志；已停止批次，不会自动重试", cause);
        }
    }

    static String run(Files files, long expectedSize, long expectedModified) throws IOException {
        if (!files.supported()) throw new IOException("此位置未声明支持同卷系统移动，原文件未处理");
        long size = files.sourceSize(), modified = files.sourceModified();
        if ((expectedSize >= 0 && expectedSize != size)
                || (expectedModified > 0 && expectedModified != modified)) {
            throw new IOException("扫描后源文件已变化，请重新扫描");
        }
        checkCancelled(files);
        byte[] hash;
        try (InputStream source = files.readSource()) { hash = hash(source, files, true); }
        String text = hex(hash);
        if (files.targetExists()) throw new IOException("目标存在同名文件，已跳过；不会覆盖或改名原件");
        files.record("NATIVE_MOVE_PENDING", text);
        checkCancelled(files);
        try (InputStream source = files.readSource()) {
            if (!Arrays.equals(hash, hash(source, files, true))) {
                throw new IOException("移动前源内容已变化，原文件未处理");
            }
        }
        if (files.sourceSize() != size || files.sourceModified() != modified) {
            throw new IOException("移动前源文件已变化，原文件未处理");
        }
        if (files.targetExists()) throw new IOException("目标出现同名文件，原文件未处理");
        checkCancelled(files);
        try {
            files.move();
            files.record("NATIVE_MOVE_RETURNED", text);
            // After the move, finish verification even if cancellation was requested.
            try (InputStream target = files.readTarget()) {
                if (!Arrays.equals(hash, hash(target, files, false))) {
                    throw new IOException("移动后目标内容校验失败");
                }
            }
            files.record("NATIVE_MOVED", text);
            return text;
        } catch (IOException | RuntimeException e) {
            throw new UncertainException(e);
        }
    }

    private static void checkCancelled(Files files) throws IOException {
        if (files.cancelled()) throw new IOException("已取消，未请求移动");
    }

    private static byte[] hash(InputStream stream, Files files, boolean cancellable) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        byte[] buffer = new byte[256 * 1024];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (cancellable) checkCancelled(files);
            digest.update(buffer, 0, length);
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
}
