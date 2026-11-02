package app.devicesorter;

import android.content.ContentResolver;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.OutputStream;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@SuppressWarnings("deprecation")
public class StorageDeviceTest extends InstrumentationTestCase {
    public void testTargetSyncPreservesWrittenBytes() throws Exception {
        File target = File.createTempFile("sync-target-", ".partial",
                getInstrumentation().getTargetContext().getCacheDir());
        ContentResolver resolver = getInstrumentation().getTargetContext().getContentResolver();
        byte[] expected = "media bytes to sync".getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream out = resolver.openOutputStream(Uri.fromFile(target), "wt")) {
                assertNotNull(out);
                out.write(expected);
            }
            Storage.sync(resolver, Uri.fromFile(target));
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(target.toPath());
        }
    }

    public void testSyncFailureIsPropagated() throws Exception {
        try {
            Storage.sync(getInstrumentation().getTargetContext().getContentResolver(),
                    Uri.fromFile(new File("/dev/null")));
            fail("An unsyncable descriptor must not report successful sync");
        } catch (SyncFailedException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
