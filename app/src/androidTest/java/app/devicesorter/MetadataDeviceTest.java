package app.devicesorter;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

@SuppressWarnings("deprecation")
public class MetadataDeviceTest extends InstrumentationTestCase {
    public void testJpegModelsAndUnknown() throws Exception {
        assertEquals("Canon EOS R5", read("canon.jpg", false));
        assertEquals("iPhone 15", read("iphone.jpg", false));
        assertNull(read("unknown.jpg", false));
    }

    public void testVideoModelAndUnknown() throws Exception {
        assertEquals("iPhone 15", read("iphone.mov", true));
        assertNull(read("unknown.mp4", true));
        // Unsupported metadata is left unmatched, never inferred from file names.
        assertNull(read("android.mp4", true));
    }

    public void testFixturesAreDecodableMedia() throws Exception {
        File photo = fixture("canon.jpg");
        assertNotNull(BitmapFactory.decodeFile(photo.getAbsolutePath()));
        File video = fixture("iphone.mov");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            assertEquals("1000", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            assertNotNull(retriever.getFrameAtTime(0));
        } finally {
            retriever.release();
        }
    }

    private String read(String name, boolean video) throws Exception {
        return Metadata.read(getInstrumentation().getTargetContext().getContentResolver(),
                Uri.fromFile(fixture(name)), video);
    }

    private File fixture(String name) throws Exception {
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(), name);
        try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
             OutputStream out = Files.newOutputStream(file.toPath())) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
        }
        return file;
    }
}
