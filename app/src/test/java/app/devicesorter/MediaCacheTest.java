package app.devicesorter;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaCacheTest {
    private static MediaItem item(String mime, boolean video, int width, int height, long duration) {
        return new MediaItem("content://media/external_primary/images/media/7", "a.jpg",
                "primary:DCIM/Camera/a.jpg", mime, 100, 1_700_000_000_999L, video,
                "Canon EOS R5", "", "2026-10-29T10:00:00", "EXIF 设备", "EXIF 型号：Canon",
                "", "primary:DCIM/Camera", "content://media/external_primary/images/media/7",
                width, height, duration);
    }

    @Test public void unchangedFileReusesMetadataForAnyFolder() {
        MediaItem old = item("image/jpeg", false, 4000, 3000, 0);
        assertTrue(MediaCache.sameContent(old, 100, 1_700_000_000L, 100, 1_700_000_000L,
                "image/jpeg", false, 4000, 3000, 0));
    }

    @Test public void movedFileKeepsMetadataEvenWhenSafTimestampHadMilliseconds() {
        MediaItem old = item("image/jpeg", false, 4000, 3000, 0);
        // MediaStore stamps whole seconds; the cached item kept the SAF millisecond
        // value. Only the cached MediaStore stamp may decide whether to reread.
        assertTrue(MediaCache.sameContent(old, 100, 1_700_000_000L, 100, 1_700_000_000L,
                "image/jpeg", false, 4000, 3000, 0));
    }

    @Test public void changedSizeOrDateForcesMetadataReread() {
        MediaItem old = item("image/jpeg", false, 4000, 3000, 0);
        assertFalse(MediaCache.sameContent(old, 100, 1_700_000_000L, 101, 1_700_000_000L,
                "image/jpeg", false, 4000, 3000, 0));
        assertFalse(MediaCache.sameContent(old, 100, 1_700_000_000L, 100, 1_700_000_001L,
                "image/jpeg", false, 4000, 3000, 0));
    }

    @Test public void changedMediaShapeForcesMetadataReread() {
        MediaItem old = item("image/jpeg", false, 4000, 3000, 0);
        assertFalse(MediaCache.sameContent(old, 100, 1, 100, 1, "image/png", false, 4000, 3000, 0));
        assertFalse(MediaCache.sameContent(old, 100, 1, 100, 1, "image/jpeg", true, 4000, 3000, 0));
        assertFalse(MediaCache.sameContent(old, 100, 1, 100, 1, "image/jpeg", false, 4000, 2999, 0));
        assertFalse(MediaCache.sameContent(old, 100, 1, 100, 1, "image/jpeg", false, 4000, 3000, 5));
    }

    @Test public void missingCachedEntryIsNeverTreatedAsUnchanged() {
        assertFalse(MediaCache.sameContent(null, 100, 1, 100, 1,
                "image/jpeg", false, 4000, 3000, 0));
    }
}