package app.devicesorter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Requires an emulator-only QA tree granted through the real system folder picker. */
@SuppressWarnings("deprecation")
public class InPlaceDeviceTest extends InstrumentationTestCase {
    private Context context;
    private Uri tree, parent;
    private MainActivity activity;
    private List<MediaItem> previous;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.isWritePermission()
                    && Storage.LOCAL.equals(permission.getUri().getAuthority())
                    && Storage.id(permission.getUri()).startsWith("primary:Pictures/DeviceSorter-GlobalQA-")) {
                tree = permission.getUri();
            }
        }
        assertNotNull("Grant Pictures/DeviceSorter-GlobalQA-* in the emulator before running this class", tree);
        previous = Session.items;
        parent = Storage.folder(context.getContentResolver(), Storage.root(tree),
                "case-" + java.util.UUID.randomUUID());
        activity = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void tearDown() throws Exception {
        if (activity != null) getInstrumentation().runOnMainSync(activity::finish);
        if (previous != null) Session.saveScan(context, previous);
        super.tearDown();
    }

    public void testSystemMoveClassifiesInOriginalParentAndPreservesBytes() throws Exception {
        MediaItem item = photo("source.jpg");
        byte[] before = read(Uri.parse(item.uri));
        assertTrue("Emulator provider must declare move support",
                Storage.supportsMove(context.getContentResolver(), Uri.parse(item.uri), parent));
        execute(item, true, Collections.emptyList());
        assertTrue(Session.status, Session.status.contains("成功 1") && Session.status.contains("未完成 0"));
        assertNull(Storage.find(context.getContentResolver(), parent, item.name));
        Uri folder = archive(item.model);
        Storage.Entry destination = Storage.find(context.getContentResolver(), folder, item.name);
        assertNotNull(destination);
        assertTrue(Arrays.equals(before, read(destination.uri())));
        assertEquals(1, Storage.children(context.getContentResolver(), folder).size());
    }

    public void testCollisionPreservesBothExistingFiles() throws Exception {
        MediaItem item = photo("collision.jpg");
        byte[] before = read(Uri.parse(item.uri));
        Uri folder = archive(item.model);
        Uri existing = DocumentsContract.createDocument(context.getContentResolver(), folder, "image/jpeg", item.name);
        byte[] sentinel = new byte[] {1, 2, 3, 4};
        try (OutputStream out = context.getContentResolver().openOutputStream(existing, "wt")) {
            assertNotNull(out);
            out.write(sentinel);
        }
        execute(item, true, Collections.emptyList());
        assertTrue(Session.status, Session.status.contains("成功 0") && Session.status.contains("未完成 1"));
        assertTrue(Arrays.equals(before, read(Uri.parse(item.uri))));
        assertTrue(Arrays.equals(sentinel, read(existing)));
    }

    public void testCopyRetainsOriginalAndArchiveIsNotRescanned() throws Exception {
        MediaItem item = photo("copy.jpg");
        byte[] before = read(Uri.parse(item.uri));
        execute(item, false, Collections.emptyList());
        assertTrue(Session.status, Session.status.contains("成功 1") && Session.status.contains("未完成 0"));
        assertTrue(Arrays.equals(before, read(Uri.parse(item.uri))));
        Storage.Entry target = Storage.find(context.getContentResolver(), archive(item.model), item.name);
        assertNotNull(target);
        assertTrue(Arrays.equals(before, read(target.uri())));
        run(new Intent(context, SortService.class).setAction("scan")
                .putStringArrayListExtra("sources", new java.util.ArrayList<>(List.of(tree.toString()))));
        assertTrue(Session.status, Session.status.contains("扫描完成"));
        assertTrue(Session.items.stream().anyMatch(found -> found.uri.equals(item.uri)));
        assertFalse(Session.items.stream().anyMatch(found -> MediaPaths.excluded(found.parentId)));
    }

    public void testExcludedSourceCannotBeMoved() throws Exception {
        MediaItem item = photo("excluded.jpg");
        byte[] before = read(Uri.parse(item.uri));
        execute(item, true, List.of(item.parentId));
        assertTrue(Session.status, Session.status.contains("没有选中的文件"));
        assertTrue(Arrays.equals(before, read(Uri.parse(item.uri))));
        assertNull(Storage.find(context.getContentResolver(), parent, MediaPaths.ARCHIVE));
    }

    public void testUnknownDeviceStaysInOriginalDirectory() throws Exception {
        MediaItem item = photo("unknown.jpg");
        MediaItem unknown = new MediaItem(item.uri, item.name, item.path, item.mime, item.size,
                item.modified, false, null, "未识别", "", "", "", "", item.parentId);
        execute(unknown, true, Collections.emptyList());
        assertTrue(Session.status, Session.status.contains("没有选中的文件"));
        assertNotNull(Storage.find(context.getContentResolver(), parent, item.name));
    }

    private MediaItem photo(String name) throws Exception {
        Uri uri = DocumentsContract.createDocument(context.getContentResolver(), parent, "image/jpeg", name);
        assertNotNull(uri);
        try (InputStream input = getInstrumentation().getContext().getAssets().open("canon.jpg");
             OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            assertNotNull(out);
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) out.write(buffer, 0, size);
        }
        Storage.Entry entry = Storage.stat(context.getContentResolver(), uri);
        return new MediaItem(uri.toString(), name, name, "image/jpeg", entry.size(), entry.modified(),
                false, "Canon EOS R5", "", "", "", "", "", DocumentsContract.getDocumentId(parent));
    }

    private Uri archive(String model) throws Exception {
        Uri root = Storage.folder(context.getContentResolver(), parent, MediaPaths.ARCHIVE);
        return Storage.folder(context.getContentResolver(), root, Naming.folder(model));
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            assertNotNull(in);
            byte[] buffer = new byte[4096];
            int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
            return out.toByteArray();
        }
    }

    private void execute(MediaItem item, boolean move, List<String> excluded) throws Exception {
        Session.saveScan(context, List.of(item));
        run(new Intent(context, SortService.class).setAction("execute").putExtra("inPlace", true)
                .putExtra("move", move).putExtra("rule", "device")
                .putStringArrayListExtra("groups", new java.util.ArrayList<>(List.of("Canon EOS R5")))
                .putStringArrayListExtra("excludedFolders", new java.util.ArrayList<>(excluded))
                .putStringArrayListExtra("sources", new java.util.ArrayList<>()));
    }

    private void run(Intent intent) throws Exception {
        Session.status = "QA waiting";
        context.startForegroundService(intent);
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        while ((Session.status.equals("QA waiting") || Session.running)
                && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
        assertFalse("Service did not start", Session.status.equals("QA waiting"));
        assertFalse("Service timed out", Session.running);
    }
}
