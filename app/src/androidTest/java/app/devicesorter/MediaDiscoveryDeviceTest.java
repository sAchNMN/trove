package app.devicesorter;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("deprecation")
public class MediaDiscoveryDeviceTest extends InstrumentationTestCase {
    private final List<Uri> created = new ArrayList<>();

    public void testDiscoversMultipleFoldersButNotArchivePendingOrTrash() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        boolean hadPermission = MediaDiscovery.granted(context, permission);
        if (!hadPermission) getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), permission);
        String prefix = "DeviceSorterDiscovery-" + java.util.UUID.randomUUID();
        String album = "Pictures/" + prefix;
        boolean writableAlbum = false;
        for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && grant.isWritePermission()
                    && Storage.LOCAL.equals(grant.getUri().getAuthority())
                    && Storage.id(grant.getUri()).startsWith("primary:Pictures/DeviceSorter-GlobalQA-")) {
                album = Storage.id(grant.getUri()).substring("primary:".length()) + "/" + prefix;
                writableAlbum = true;
            }
        }
        final String albumParent = "primary:" + album;
        final boolean expectDocument = writableAlbum;
        try {
            Uri camera = insert("DCIM/" + prefix + "/", "camera.jpg", false, false);
            Uri pictures = insert(album + "/", "album.jpg", false, false);
            Uri archive = insert(album + "/分类/Canon/", "archived.jpg", false, false);
            Uri pending = insert(album + "/", "pending.jpg", true, false);
            Uri trash = Build.VERSION.SDK_INT >= 30
                    ? insert(album + "/", "trashed.jpg", false, true) : null;
            List<MediaDiscovery.Candidate> rows = MediaDiscovery.list(context);
            assertTrue(rows.stream().anyMatch(item -> item.uri().equals(camera)
                    && item.parentId().equals("primary:DCIM/" + prefix)));
            assertTrue(rows.stream().anyMatch(item -> item.uri().equals(pictures)
                    && item.parentId().equals(albumParent)));
            assertFalse(rows.stream().anyMatch(item -> item.uri().equals(archive) || item.uri().equals(pending)
                    || item.uri().equals(trash)));
            List<MediaItem> previous = Session.items;
            MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                Session.status = "QA discovery waiting";
                context.startForegroundService(new Intent(context, SortService.class).setAction("scan_all"));
                long deadline = android.os.SystemClock.uptimeMillis() + 60000;
                while ((Session.status.equals("QA discovery waiting") || Session.running)
                        && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
                assertFalse("Media discovery timed out", Session.running);
                assertTrue(Session.status, Session.status.startsWith("媒体库查找完成"));
                assertTrue(Session.items.stream().anyMatch(item -> item.uri.equals(camera.toString())));
                assertTrue(Session.items.stream().anyMatch(item -> item.parentId.equals(albumParent)
                        && item.name.equals("album.jpg")
                        && (!expectDocument || Storage.LOCAL.equals(Uri.parse(item.uri).getAuthority()))));
                assertFalse(Session.items.stream().anyMatch(item -> item.uri.equals(archive.toString())
                        || item.uri.equals(pending.toString())));
            } finally {
                getInstrumentation().runOnMainSync(activity::finish);
                Session.saveScan(context, previous);
            }
        } finally {
            for (Uri uri : created) context.getContentResolver().delete(uri, null, null);
            created.clear();
        }
    }

    public void testPermissionScopeMatchesActualGrants() {
        Context context = getInstrumentation().getTargetContext();
        String expected = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("expectedScope");
        if ("partial".equals(expected)) {
            assertTrue("Test setup must grant partial access", MediaDiscovery.accessible(context));
            assertFalse(MediaDiscovery.full(context));
            assertTrue(MediaDiscovery.scope(context).contains("不完整"));
        } else assertEquals(!MediaDiscovery.full(context), MediaDiscovery.scope(context).contains("不完整"));
    }

    public void testParentIdentitySurvivesManualAssignmentAndSnapshot() throws Exception {
        MediaItem item = new MediaItem("content://media/external/images/media/1", "a.jpg", "primary:Pictures/a.jpg",
                "image/jpeg", 10, 20, false, "Canon", "", "", "", "", "", "primary:Pictures");
        assertEquals(item.parentId, MediaItem.from(item.withFolder("旅行").json()).parentId);
    }

    public void testNoWriteGrantCannotMoveDiscoveredFile() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        String path = "Pictures/DeviceSorter-NoGrant-" + java.util.UUID.randomUUID();
        String name = "source-" + java.util.UUID.randomUUID() + ".jpg";
        Uri source = insert(path + "/", name, false, false);
        List<MediaItem> previous = Session.items;
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            MediaDiscovery.State state = MediaDiscovery.stat(context, source);
            MediaItem item = new MediaItem(source.toString(), name, path + "/" + name,
                    "image/jpeg", state.size(), state.modified(), false, "Canon EOS R5", "", "", "", "", "", "primary:" + path);
            Session.saveScan(context, List.of(item));
            Session.status = "QA authorization waiting";
            context.startForegroundService(new Intent(context, SortService.class).setAction("execute")
                    .putExtra("inPlace", true).putExtra("move", true).putExtra("rule", "device")
                    .putStringArrayListExtra("groups", new java.util.ArrayList<>(List.of("Canon EOS R5")))
                    .putStringArrayListExtra("sources", new java.util.ArrayList<>()));
            long deadline = android.os.SystemClock.uptimeMillis() + 10000;
            while ((Session.status.equals("QA authorization waiting") || Session.running)
                    && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
            assertTrue(Session.status, Session.status.contains("成功 0") && Session.status.contains("未完成 1"));
            assertEquals("Canon EOS R5", Metadata.read(context.getContentResolver(), source, false));
            Uri tree = null;
            for (android.content.UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
                if (grant.isReadPermission() && grant.isWritePermission()
                        && Storage.LOCAL.equals(grant.getUri().getAuthority())
                        && Storage.id(grant.getUri()).startsWith("primary:Pictures/DeviceSorter-GlobalQA-")) tree = grant.getUri();
            }
            assertNotNull("Grant emulator QA directory before testing independent copy", tree);
            Session.saveScan(context, List.of(item));
            Session.status = "QA copy waiting";
            context.startForegroundService(new Intent(context, SortService.class).setAction("execute")
                    .putExtra("inPlace", false).putExtra("move", false).putExtra("rule", "device")
                    .putExtra("target", tree.toString())
                    .putStringArrayListExtra("groups", new java.util.ArrayList<>(List.of("Canon EOS R5")))
                    .putStringArrayListExtra("sources", new java.util.ArrayList<>()));
            deadline = android.os.SystemClock.uptimeMillis() + 10000;
            while ((Session.status.equals("QA copy waiting") || Session.running)
                    && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50);
            assertTrue(Session.status, Session.status.contains("成功 1") && Session.status.contains("未完成 0"));
            assertEquals("Canon EOS R5", Metadata.read(context.getContentResolver(), source, false));
            Storage.Entry folder = Storage.find(context.getContentResolver(), Storage.root(tree), "Canon EOS R5");
            assertNotNull(folder);
            Storage.Entry copy = Storage.find(context.getContentResolver(), folder.uri(), name);
            assertNotNull(copy);
            assertEquals("Canon EOS R5", Metadata.read(context.getContentResolver(), copy.uri(), false));
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
            Session.saveScan(context, previous);
            context.getContentResolver().delete(source, null, null);
            created.clear();
        }
    }

    private Uri insert(String path, String name, boolean pending, boolean trash) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        created.add(uri);
        try (InputStream in = getInstrumentation().getContext().getAssets().open("canon.jpg");
             OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(out);
            byte[] buffer = new byte[4096];
            int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, pending ? 1 : 0);
        if (Build.VERSION.SDK_INT >= 30) values.put(MediaStore.MediaColumns.IS_TRASHED, trash ? 1 : 0);
        context.getContentResolver().update(uri, values, null, null);
        return uri;
    }
}
