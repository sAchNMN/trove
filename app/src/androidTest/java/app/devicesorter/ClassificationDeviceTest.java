package app.devicesorter;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@SuppressWarnings("deprecation")
public class ClassificationDeviceTest extends InstrumentationTestCase {
    public void testMultiplePhotoManufacturersAndCaptureTime() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File file = File.createTempFile("metadata-", ".jpg", context.getCacheDir());
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open("canon.jpg")) {
                Files.copy(input, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            for (String[] device : new String[][]{{"Apple", "iPhone 15"}, {"samsung", "SM-S928B"},
                    {"vivo", "V2415"}, {"Canon", "Canon EOS R5"}, {"SONY", "ILCE-7M4"}}) {
                android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
                exif.setAttribute(android.media.ExifInterface.TAG_MAKE, device[0]);
                exif.setAttribute(android.media.ExifInterface.TAG_MODEL, device[1]);
                exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2024:02:29 23:50:00");
                exif.saveAttributes();
                Metadata.Info info = Metadata.inspect(context.getContentResolver(), Uri.fromFile(file), false);
                assertEquals(Naming.device(device[0], device[1]), info.model());
                assertEquals("2024-02-29T23:50:00", info.capturedAt());
            }
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    public void testManualDecisionAndMetadataSurviveSnapshotReload() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        List<MediaItem> previous = Session.items;
        try {
            MediaItem original = photo(null, "");
            MediaItem manual = original.withFolder("家庭/待整理");
            Session.saveScan(context, Collections.singletonList(manual));
            Session.items = Collections.emptyList();
            Session.load(context);
            MediaItem restored = Session.items.get(0);
            assertNull(restored.model);
            assertEquals("", restored.capturedAt);
            assertEquals("家庭/待整理", Classification.folder(restored, Classification.DEFAULT));
            assertEquals("EXIF", restored.metadataSource);
            assertEquals("ISO 100", restored.parameters);
            assertNull(Classification.folder(restored.withFolder(""), Classification.DEFAULT));
            org.json.JSONObject old = original.json();
            old.remove("capturedAt");
            old.remove("manualFolder");
            old.remove("metadataSource");
            old.remove("parameters");
            assertNull(Classification.folder(MediaItem.from(old), Classification.DEFAULT));
        } finally { Session.saveScan(context, previous); }
    }

    public void testActivityStrategiesAndManualAssignmentUseSamePreview() throws Exception {
        Instrumentation instrumentation = getInstrumentation();
        Context context = instrumentation.getTargetContext();
        List<MediaItem> previous = Session.items;
        String oldRule = context.getSharedPreferences("settings", 0).getString("rule", Classification.DEFAULT);
        Session.saveScan(context, Arrays.asList(photo("iPhone 15", "2026-10-29T12:00:00"), photo(null, "")));
        context.getSharedPreferences("settings", 0).edit().putString("rule", Classification.DEFAULT).commit();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                try {
                    Field summary = MainActivity.class.getDeclaredField("summaryPending");
                    summary.setAccessible(true);
                    assertEquals("1", ((TextView) summary.get(activity)).getText().toString());
                    Method assign = MainActivity.class.getDeclaredMethod("assignFolders", List.class, String.class);
                    assign.setAccessible(true);
                    assertEquals(Boolean.TRUE, assign.invoke(activity, Collections.singletonList(Session.items.get(1)), "家庭/待整理"));
                    assertEquals("0", ((TextView) summary.get(activity)).getText().toString());
                    Method apply = MainActivity.class.getDeclaredMethod("applyRule", String.class);
                    apply.setAccessible(true);
                    apply.invoke(activity, "day/device/type");
                    assertEquals("2026-10-29/iPhone 15/照片", Classification.folder(Session.items.get(0), "day/device/type"));
                    assertEquals("家庭/待整理", Classification.folder(Session.items.get(1), "day/device/type"));
                    assertEquals(Boolean.TRUE, assign.invoke(activity, Collections.singletonList(Session.items.get(1)), ""));
                    assertEquals("1", ((TextView) summary.get(activity)).getText().toString());
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
        } finally {
            instrumentation.runOnMainSync(activity::finish);
            Session.saveScan(context, previous);
            context.getSharedPreferences("settings", 0).edit().putString("rule", oldRule).commit();
        }
    }

    public void testDeselectedGroupsSurviveActivityRecreation() throws Exception {
        Instrumentation instrumentation = getInstrumentation();
        Context context = instrumentation.getTargetContext();
        List<MediaItem> previous = Session.items;
        String oldRule = context.getSharedPreferences("settings", 0).getString("rule", Classification.DEFAULT);
        Session.saveScan(context, Collections.singletonList(photo("iPhone 15", "2026-10-29T12:00:00")));
        context.getSharedPreferences("settings", 0).edit().putString("rule", Classification.DEFAULT).commit();
        MainActivity original = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        MainActivity recreated = null;
        try {
            Field selected = MainActivity.class.getDeclaredField("selected");
            selected.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { ((Set<?>) selected.get(original)).clear(); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
                original.recreate();
            });
            recreated = (MainActivity) monitor.waitForActivityWithTimeout(10000);
            assertNotNull(recreated);
            assertTrue(((Set<?>) selected.get(recreated)).isEmpty());
        } finally {
            instrumentation.removeMonitor(monitor);
            MainActivity activity = recreated == null ? original : recreated;
            instrumentation.runOnMainSync(activity::finish);
            Session.saveScan(context, previous);
            context.getSharedPreferences("settings", 0).edit().putString("rule", oldRule).commit();
        }
    }

    private MediaItem photo(String model, String date) {
        return new MediaItem(model == null ? "file:unknown" : "file:known", "photo.jpg", "Camera/photo.jpg",
                "image/jpeg", 20, 30, false, model, "", date, "EXIF", "ISO 100", "");
    }
}
