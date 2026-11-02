package app.devicesorter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@SuppressWarnings("deprecation")
public class MainActivityDeviceTest extends InstrumentationTestCase {
    public void testPendingExportSurvivesActivityRecreation() throws Exception {
        Instrumentation instrumentation = getInstrumentation();
        File source = File.createTempFile("export-source-", ".jsonl",
                instrumentation.getTargetContext().getCacheDir());
        File destination = File.createTempFile("export-target-", ".jsonl",
                instrumentation.getTargetContext().getCacheDir());
        byte[] expected = "{\"status\":\"FINISHED\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), expected);
        Field exporting = MainActivity.class.getDeclaredField("exporting");
        exporting.setAccessible(true);
        MainActivity original = (MainActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        MainActivity recreated = null;
        try {
            instrumentation.runOnMainSync(() -> {
                try {
                    exporting.set(original, source);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
                original.recreate();
            });
            recreated = (MainActivity) monitor.waitForActivityWithTimeout(10000);
            assertNotNull("Activity was not recreated", recreated);
            assertEquals("Pending log must survive recreation", source, exporting.get(recreated));
            MainActivity activity = recreated;
            instrumentation.runOnMainSync(() -> activity.onActivityResult(12, Activity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(destination))));
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            while (destination.length() != expected.length
                    && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        } finally {
            instrumentation.removeMonitor(monitor);
            MainActivity activity = recreated == null ? original : recreated;
            instrumentation.runOnMainSync(activity::finish);
            Files.deleteIfExists(source.toPath());
            Files.deleteIfExists(destination.toPath());
        }
    }
}
