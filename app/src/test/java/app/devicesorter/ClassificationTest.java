package app.devicesorter;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClassificationTest {
    private MediaItem item(String model, String date) {
        return new MediaItem("file:a", "a.jpg", "Camera/a.jpg", "image/jpeg", 10, 20,
                false, model, "", date, "", "", "");
    }

    @Test public void recommendedRuleNeedsBothDeviceAndCaptureDate() {
        assertEquals("iPhone 15/2026-10", Classification.folder(item("iPhone 15", "2026-10-29T23:59:00-07:00"), Classification.DEFAULT));
        assertNull(Classification.folder(item("iPhone 15", ""), Classification.DEFAULT));
        assertNull(Classification.folder(item(null, "2026-10-29T12:00:00"), Classification.DEFAULT));
        assertNull(Classification.folder(item(null, ""), Classification.DEFAULT));
    }

    @Test public void onlySelectedFieldsAreRequired() {
        assertEquals("Canon EOS R5", Classification.folder(item("Canon EOS R5", ""), "device"));
        assertEquals("2026-10", Classification.folder(item(null, "2026-10-29T12:00:00"), "month"));
        assertEquals("2026-10-29", Classification.folder(item(null, "2026-10-29T12:00:00"), "day"));
        assertNull(Classification.folder(item("Canon", "2026-02-30T12:00:00"), "month"));
        assertFalse(Classification.missing(item(null, ""), Classification.DEFAULT).isEmpty());
    }

    @Test public void customHierarchyUsesRecordedLocalDateAndSafeDeviceName() {
        MediaItem item = item("Sony/Camera", "2026-12-31T23:59:00-08:00");
        assertEquals("2026/2026-12/" + Naming.folder(item.model) + "/照片",
                Classification.folder(item, "year/month/device/type"));
    }

    @Test public void flatRulePutsFilesDirectlyInTargetWithoutSubfolders() {
        MediaItem item = item(null, "");
        assertTrue(Classification.valid(Classification.FLAT));
        assertEquals("", Classification.folder(item, Classification.FLAT));
        assertEquals("", Classification.missing(item, Classification.FLAT));
        assertEquals("不建子目录", Classification.title(Classification.FLAT));
    }

    @Test public void manualChoiceDoesNotInventMetadataAndCanBeReversed() {
        MediaItem original = item(null, "");
        MediaItem manual = original.withFolder("家庭相册/待整理");
        assertEquals("家庭相册/待整理", Classification.folder(manual, Classification.DEFAULT));
        assertNull(manual.model);
        assertEquals("", manual.capturedAt);
        assertNull(Classification.folder(manual.withFolder(""), Classification.DEFAULT));
        assertNull(Classification.folder(original, Classification.DEFAULT));
    }

    @Test public void rulesRejectUnknownDuplicateOrEvidenceFreeFields() {
        for (String rule : new String[]{"", "type", "device/device", "device/../month", "day/", "filedate"}) {
            assertFalse(rule, Classification.valid(rule));
        }
        assertTrue(Classification.valid("day/device/type"));
    }

    @Test public void manualPathsRejectTraversalAndUnsafeNames() {
        assertEquals("家庭/旅行", Classification.manualPath(" 家庭 / 旅行 "));
        for (String path : new String[]{"", "..", "A/../B", "/A", "A//B", "A\\B", "A:B", "A.", "A\nB", "A/B/C/D/E/F"}) {
            try {
                Classification.manualPath(path);
                fail(path);
            } catch (IllegalArgumentException expected) { /* Invalid input remains in the editor. */ }
        }
    }
}
