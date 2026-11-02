package app.devicesorter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class Transfer {
    interface Files {
        InputStream readSource() throws IOException;
        InputStream readTarget() throws IOException;
        OutputStream createTarget() throws IOException;
        void syncTarget() throws IOException;
        long sourceSize() throws IOException;
        long sourceModified() throws IOException;
        void deleteSource() throws IOException;
        void deletePartialTarget() throws IOException;
        void record(String status, String hash) throws IOException;
        boolean cancelled();
    }

    record Outcome(String status, String hash) {}

    static Outcome run(Files files, boolean move, long expectedSize, long expectedModified)
            throws IOException {
        long initialSize = files.sourceSize();
        long initialModified = files.sourceModified();
        if ((expectedSize >= 0 && initialSize != expectedSize)
                || (expectedModified > 0 && initialModified != expectedModified)) {
            throw new IOException("扫描后源文件已发生变化，请重新扫描");
        }
        boolean verified = false;
        byte[] copiedHash;
        long count = 0;
        try {
            MessageDigest digest = sha256();
            try (InputStream in = files.readSource(); OutputStream out = files.createTarget()) {
                byte[] buffer = new byte[256 * 1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    checkCancelled(files);
                    out.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                    count += length;
                }
                out.flush();
            }
            files.syncTarget();
            copiedHash = digest.digest();
            if (initialSize >= 0 && count != initialSize) throw new IOException("复制字节数不一致");
            byte[] targetHash;
            try (InputStream target = files.readTarget()) {
                targetHash = hash(target, files);
            }
            if (!Arrays.equals(copiedHash, targetHash)) throw new IOException("目标 SHA-256 校验失败");
            checkCancelled(files);
            if (files.sourceSize() != initialSize || files.sourceModified() != initialModified) {
                throw new IOException("复制期间源文件发生变化，已保留原文件");
            }
            verified = true;
        } catch (IOException | RuntimeException e) {
            if (!verified) {
                try {
                    files.deletePartialTarget();
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        String hash = hex(copiedHash);
        // Flush the verified destination to the journal before any destructive operation.
        files.record("VERIFIED_COPY", hash);
        checkCancelled(files);
        if (move) files.record("DELETE_PENDING", hash);
        // Finalization may rename the target; verify the final URI after journal writes.
        files.syncTarget();
        try (InputStream target = files.readTarget()) {
            if (!Arrays.equals(copiedHash, hash(target, files))) {
                throw new IOException("最终目标 SHA-256 校验失败，保留原文件");
            }
        }
        checkCancelled(files);
        try (InputStream source = files.readSource()) {
            if (!Arrays.equals(copiedHash, hash(source, files))) {
                throw new IOException("最终源文件校验不一致，保留两份文件");
            }
        }
        checkCancelled(files);
        if (files.sourceSize() != initialSize || files.sourceModified() != initialModified) {
            throw new IOException("最终源文件已变化，保留两份文件");
        }
        checkCancelled(files);
        if (!move) return new Outcome("COPIED", hash);
        files.deleteSource();
        return new Outcome("MOVED", hash);
    }

    private static void checkCancelled(Files files) throws IOException {
        if (files.cancelled()) throw new IOException("操作已取消，原文件保留");
    }

    private static byte[] hash(InputStream in, Files files) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[256 * 1024];
        int length;
        while ((length = in.read(buffer)) != -1) {
            checkCancelled(files);
            digest.update(buffer, 0, length);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
