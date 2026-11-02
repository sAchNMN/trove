package app.devicesorter;

import org.junit.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class DuplicatesTest {
    private MediaItem item(String name, long size, String parent) {
        return new MediaItem("file:" + name, name, parent + "/" + name, "image/jpeg",
                size, 1, false, "vivo X200 Pro mini", "", "2026-10-03T17:06:05",
                "EXIF", "", "", parent);
    }

    @Test public void groupsOnlySameSizeAndSameHash() throws Exception {
        MediaItem a = item("a.jpg", 100, "primary:DCIM/Camera");
        MediaItem b = item("b.jpg", 100, "primary:Pictures/旅行");
        MediaItem c = item("c.jpg", 100, "primary:Pictures/其他");
        MediaItem d = item("d.jpg", 200, "primary:DCIM/Camera");
        Map<String, String> hashes = Map.of("a.jpg", "same", "b.jpg", "same", "c.jpg", "different", "d.jpg", "same");
        Duplicates.Result result = Duplicates.find(List.of(a, b, c, d),
                value -> hashes.get(value.name), () -> false, percent -> { });
        assertEquals(1, result.groups().size());
        assertEquals(List.of(a, b), result.groups().get(0).items());
        assertEquals(100, result.groups().get(0).wasted());
        assertEquals(3, result.compared());
    }

    @Test public void unreadableFileIsSkippedWithoutInventingAGroup() throws Exception {
        MediaItem a = item("a.jpg", 100, "primary:DCIM/Camera");
        MediaItem b = item("b.jpg", 100, "primary:Pictures/旅行");
        MediaItem c = item("c.jpg", 100, "primary:Pictures/其他");
        Duplicates.Result result = Duplicates.find(List.of(a, b, c), value -> {
            if (value.name.equals("b.jpg")) throw new IOException("denied");
            return "same";
        }, () -> false, percent -> { });
        assertEquals(1, result.groups().size());
        assertEquals(List.of(a, c), result.groups().get(0).items());
        assertEquals(1, result.skipped());
    }

    @Test public void cancellationAndZeroSizeAreHandled() throws Exception {
        MediaItem a = item("a.jpg", 100, "primary:DCIM/Camera");
        MediaItem b = item("b.jpg", 100, "primary:Pictures/旅行");
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> Duplicates.find(List.of(a, b),
                value -> "same", cancelled::get, percent -> { }));
        Duplicates.Result empty = Duplicates.find(
                List.of(item("z.jpg", 0, "primary:DCIM/Camera")),
                value -> "same", () -> false, percent -> { });
        assertTrue(empty.groups().isEmpty());
    }

    @Test public void sameNameCountIsDisplayOnly() {
        Duplicates.Group group = new Duplicates.Group("h", 100, List.of(
                item("same.jpg", 100, "primary:DCIM/Camera"),
                item("same.jpg", 100, "primary:Pictures/旅行"),
                item("other.jpg", 100, "primary:Pictures/其他")));
        assertEquals(2, group.sameNameCount());
    }

    @Test public void trashableOnlyIncludesMediaStoreBackedFiles() {
        MediaItem media = new MediaItem("file:a", "a.jpg", "primary:DCIM/Camera/a.jpg", "image/jpeg",
                10, 20, false, "vivo", "", "", "", "", "", "primary:DCIM/Camera",
                "content://media/external/images/media/1", 1, 1, 0);
        MediaItem document = item("b.jpg", 10, "primary:DCIM/Camera");
        Duplicates.Group group = new Duplicates.Group("h", 10, List.of(media, document));
        assertEquals(List.of(media), Duplicates.trashable(group));
    }
}