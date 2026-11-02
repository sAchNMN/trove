package app.devicesorter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Device check for the incremental media sync: a second pass must reuse cached
 * metadata, a new file must be discovered, and a removed file must disappear.
 */
@SuppressWarnings("deprecation")
public class MediaLibraryDeviceTest extends InstrumentationTestCase {
    private final List<Uri> created = new ArrayList<>();

    public void testSyncReusesCacheAndDetectsNewAndRemovedFiles() throws Exception {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            grant(Manifest.permission.READ_MEDIA_IMAGES);
            grant(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            grant(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        assertTrue("media permission is required", MediaDiscovery.accessible(context));
        Session.cancel = false;

        String folder = "Pictures/DeviceSorterIncremental-" + java.util.UUID.randomUUID();
        String first = "incremental-a-" + java.util.UUID.randomUUID() + ".jpg";
        String second = "incremental-b-" + java.util.UUID.randomUUID() + ".jpg";
        String third = "incremental-c-" + java.util.UUID.randomUUID() + ".jpg";
        Uri a = insert(folder + "/", first);
        Uri b = insert(folder + "/", second);
        try {
            Thread.sleep(500); // let MediaStore settle its published size/date_modified
            MediaLibrary.Result initial = MediaLibrary.sync(context, false, text -> { });
            assertTrue(initial.added() >= 2);
            assertTrue(contains(initial.items(), first));
            assertTrue(contains(initial.items(), second));

            MediaLibrary.Result unchanged = MediaLibrary.sync(context, false, text -> { });
            assertEquals(0, unchanged.added());
            assertEquals(0, unchanged.updated());
            assertEquals(0, unchanged.removed());
            assertTrue("cached files must not be reopened", unchanged.reused() >= 2);
            assertTrue(contains(unchanged.items(), first));
            assertTrue(contains(unchanged.items(), second));
            assertEquals("Canon EOS R5", item(unchanged.items(), first).model);

            assertTrue(context.getContentResolver().delete(a, null, null) > 0);
            Uri c = insert(folder + "/", third);
            Thread.sleep(500);
            MediaLibrary.Result changed = MediaLibrary.sync(context, false, text -> { });
            assertTrue("removed file must disappear", changed.removed() >= 1);
            assertTrue("new file must be discovered", changed.added() >= 1);
            assertFalse(contains(changed.items(), first));
            assertTrue(contains(changed.items(), third));
            assertTrue("existing file must keep its cached metadata", changed.reused() >= 1);
        } finally {
            for (Uri uri : created) {
                try { context.getContentResolver().delete(uri, null, null); }
                catch (RuntimeException ignored) { }
            }
            created.clear();
            // Leave the next test with a cache that no longer contains the fixtures.
            try { MediaLibrary.sync(context, false, text -> { }); }
            catch (Exception ignored) { }
        }
    }

    private void grant(String permission) {
        Context context = getInstrumentation().getTargetContext();
        if (!MediaDiscovery.granted(context, permission)) {
            getInstrumentation().getUiAutomation().grantRuntimePermission(
                    context.getPackageName(), permission);
        }
    }

    private Uri insert(String path, String name) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        created.add(uri);
        try (InputStream in = getInstrumentation().getContext().getAssets().open("canon.jpg");
             OutputStream out = resolver.openOutputStream(uri)) {
            assertNotNull(out);
            byte[] buffer = new byte[4096];
            int size;
            while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private static boolean contains(List<MediaItem> items, String name) {
        return items.stream().anyMatch(item -> item.name.equals(name));
    }

    private static MediaItem item(List<MediaItem> items, String name) {
        return items.stream().filter(item -> item.name.equals(name)).findFirst().orElseThrow();
    }
}