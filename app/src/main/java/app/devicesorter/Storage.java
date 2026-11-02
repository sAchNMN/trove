package app.devicesorter;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Storage {
    static final String LOCAL = "com.android.externalstorage.documents";
    record Entry(Uri uri, String name, String mime, long size, long modified) {
        boolean directory() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    static String id(Uri tree) throws IOException {
        if (!LOCAL.equals(tree.getAuthority())) throw new IOException("请选择手机内部存储或 SD 卡，不支持云盘");
        try {
            return DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            throw new IOException("目录授权无效，请重新选择", e);
        }
    }

    static Uri root(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    static Entry stat(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, projection(), null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("文件不存在或授权已失效");
            return entry(uri, cursor);
        } catch (RuntimeException e) {
            throw new IOException("无法读取文件信息：" + e.getMessage(), e);
        }
    }

    static List<Entry> children(ContentResolver resolver, Uri parent) throws IOException {
        List<Entry> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
                DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(children, projection(), null, null, null)) {
            if (cursor == null) throw new IOException("无法列出目录");
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                result.add(entry(DocumentsContract.buildDocumentUriUsingTree(parent, documentId), cursor));
            }
        } catch (RuntimeException e) {
            throw new IOException("无法遍历目录：" + e.getMessage(), e);
        }
        return result;
    }

    static Entry find(ContentResolver resolver, Uri parent, String name) throws IOException {
        for (Entry item : children(resolver, parent)) {
            if (name.equalsIgnoreCase(item.name())) return item;
        }
        return null;
    }

    static Uri folder(ContentResolver resolver, Uri parent, String name) throws IOException {
        Entry existing = find(resolver, parent, name);
        if (existing != null) {
            if (!existing.directory()) throw new IOException("目标中存在同名文件：" + name);
            return existing.uri();
        }
        Uri created = DocumentsContract.createDocument(resolver, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("无法建立设备目录");
        return created;
    }

    static void sync(ContentResolver resolver, Uri uri) throws IOException {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
            if (descriptor == null) throw new IOException("无法打开目标文件进行同步");
            descriptor.getFileDescriptor().sync();
        }
    }

    /**
     * A snapshot of the persisted SAF tree grants. Resolving thousands of document
     * IDs through ContentResolver.getPersistedUriPermissions() per file is a binder
     * call each time; one snapshot turns that into an in-memory path check.
     */
    static final class Grants {
        private record Tree(Uri uri, String rootId) {}
        private final List<Tree> trees;
        private final String signature;

        private Grants(List<Tree> trees) {
            this.trees = trees;
            StringBuilder value = new StringBuilder();
            for (Tree tree : trees) {
                if (value.length() > 0) value.append('\n');
                value.append(tree.rootId());
            }
            signature = value.toString();
        }

        String signature() { return signature; }

        Uri document(String documentId) throws IOException {
            if (documentId == null || documentId.indexOf(':') < 1) {
                throw new IOException("文件位置无效，请重新扫描");
            }
            for (Tree tree : trees) {
                if (MediaPaths.contains(tree.rootId(), documentId)) {
                    // Preserve the provider's volume spelling (SD UUIDs may be uppercase).
                    String actual = tree.rootId().substring(0, tree.rootId().indexOf(':'))
                            + documentId.substring(documentId.indexOf(':'));
                    return DocumentsContract.buildDocumentUriUsingTree(tree.uri(), actual);
                }
            }
            throw new IOException("来源目录尚未获得读写授权：" + documentId + "；请添加该目录后重新扫描");
        }
    }

    static Grants grants(ContentResolver resolver) {
        List<Grants.Tree> trees = new ArrayList<>();
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            Uri tree = permission.getUri();
            if (!LOCAL.equals(tree.getAuthority()) || !permission.isReadPermission()
                    || !permission.isWritePermission()) continue;
            try {
                trees.add(new Grants.Tree(tree, id(tree)));
            } catch (IOException ignored) {
                // A malformed grant cannot resolve anything.
            }
        }
        trees.sort((left, right) -> left.rootId().compareTo(right.rootId()));
        return new Grants(trees);
    }

    static Uri authorizedDocument(ContentResolver resolver, String documentId) throws IOException {
        return grants(resolver).document(documentId);
    }

    /**
     * Whether a persisted read grant still covers this document. Scanning a source only
     * needs read access, so a grant without write must not be reported as missing.
     */
    static boolean readable(ContentResolver resolver, String documentId) {
        if (documentId == null || documentId.indexOf(':') < 1) return false;
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            Uri tree = permission.getUri();
            if (!permission.isReadPermission() || !LOCAL.equals(tree.getAuthority())) continue;
            try {
                if (MediaPaths.contains(id(tree), documentId)) return true;
            } catch (IOException ignored) {
                // A malformed grant cannot cover anything.
            }
        }
        return false;
    }

    static boolean supportsMove(ContentResolver resolver, Uri source, Uri targetParent) throws IOException {
        String sourceId = DocumentsContract.getDocumentId(source);
        String targetId = DocumentsContract.getDocumentId(targetParent);
        if (!LOCAL.equals(source.getAuthority()) || !LOCAL.equals(targetParent.getAuthority())
                || !sourceId.split(":", 2)[0].equalsIgnoreCase(targetId.split(":", 2)[0])) return false;
        try (Cursor cursor = resolver.query(source,
                new String[] { DocumentsContract.Document.COLUMN_FLAGS }, null, null, null)) {
            return cursor != null && cursor.moveToFirst()
                    && (cursor.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_MOVE) != 0;
        }
    }

    private static String[] projection() {
        return new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    }

    private static Entry entry(Uri uri, Cursor c) {
        return new Entry(uri, c.getString(1), c.getString(2),
                c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4));
    }
}
