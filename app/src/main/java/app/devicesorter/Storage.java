package app.devicesorter;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
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
