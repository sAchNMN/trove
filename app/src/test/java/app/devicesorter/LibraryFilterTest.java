package app.devicesorter;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LibraryFilterTest {
    private MediaItem item(String name, boolean video, String date, String model, long size,
                           String folder, String parameters) {
        return new MediaItem("file:" + name, name, folder + "/" + name,
                video ? "video/mp4" : "image/jpeg", size, 1, video, model, "",
                date, "EXIF", parameters, "", folder);
    }

    private final MediaItem camera = item("IMG_20261003_170605.jpg", false,
            "2026-10-03T17:06:05", "vivo X200 Pro mini", 4900, "primary:DCIM/Camera", "ISO 100");
    private final MediaItem travel = item("trip.mp4", true,
            "2026-09-18T10:00:00", "iPhone 15", 2300, "primary:Pictures/旅行", "f/1.8");
    private final MediaItem screenshot = item("Screenshot.png", false,
            "", null, 700, "primary:Pictures/Screenshots", "");

    @Test public void filtersByTypeDeviceDateSizeAndFolder() {
        LibraryFilter.Query query = new LibraryFilter.Query();
        query.type = "video";
        assertEquals(List.of(travel), LibraryFilter.apply(List.of(camera, travel, screenshot), query));

        query = new LibraryFilter.Query();
        query.model = "vivo X200 Pro mini";
        assertEquals(List.of(camera), LibraryFilter.apply(List.of(camera, travel), query));

        query = new LibraryFilter.Query();
        query.from = "2026-09-01";
        query.to = "2026-09-30";
        assertEquals(List.of(travel), LibraryFilter.apply(List.of(camera, travel), query));

        query = new LibraryFilter.Query();
        query.minSize = 2000;
        query.maxSize = 3000;
        assertEquals(List.of(travel), LibraryFilter.apply(List.of(camera, travel, screenshot), query));

        query = new LibraryFilter.Query();
        query.parentId = "primary:Pictures/旅行";
        assertEquals(List.of(travel), LibraryFilter.apply(List.of(camera, travel), query));
    }

    @Test public void keywordSearchesNamesPathsDevicesAndParameters() {
        LibraryFilter.Query query = new LibraryFilter.Query();
        query.keyword = "iso 100";
        assertEquals(List.of(camera), LibraryFilter.apply(List.of(camera, travel), query));

        query.keyword = "旅行 f/1.8";
        assertEquals(List.of(travel), LibraryFilter.apply(List.of(camera, travel), query));

        query.keyword = "screenshot";
        assertEquals(List.of(screenshot), LibraryFilter.apply(List.of(camera, travel, screenshot), query));
    }

    @Test public void outsideCameraOnlyReturnsStrayMedia() {
        LibraryFilter.Query query = new LibraryFilter.Query();
        query.outsideCamera = true;
        assertEquals(List.of(travel, screenshot),
                LibraryFilter.apply(List.of(camera, travel, screenshot), query));
    }

    @Test public void dateAndSizeValidationRejectsBadInput() {
        LibraryFilter.Query query = new LibraryFilter.Query();
        query.from = "2026-13-40";
        assertThrows(IllegalArgumentException.class, query::validate);
        query = new LibraryFilter.Query();
        query.minSize = 100;
        query.maxSize = 50;
        assertThrows(IllegalArgumentException.class, query::validate);
        assertEquals(1572864, LibraryFilter.megabytes("1.5"));
        assertEquals(-1, LibraryFilter.megabytes(""));
        assertThrows(IllegalArgumentException.class, () -> LibraryFilter.megabytes("abc"));
    }

    @Test public void modelsAndFoldersAreStableSortedLists() {
        assertEquals(List.of("iPhone 15", "vivo X200 Pro mini"),
                LibraryFilter.models(List.of(camera, travel, screenshot)));
        assertEquals(List.of("primary:DCIM/Camera", "primary:Pictures/Screenshots", "primary:Pictures/旅行"),
                LibraryFilter.folders(List.of(camera, travel, screenshot)));
    }
}