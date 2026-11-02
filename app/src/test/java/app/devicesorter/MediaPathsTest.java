package app.devicesorter;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaPathsTest {
    @Test public void mapsBothPrimaryAndSdMediaWithoutInventingPaths() {
        assertEquals("primary:DCIM/Camera", MediaPaths.parentId("external_primary", "DCIM/Camera/"));
        assertEquals("1234-abcd:Pictures", MediaPaths.parentId("1234-abcd", "Pictures/"));
        assertEquals("primary:", MediaPaths.parentId("external_primary", ""));
        for (String path : new String[]{"../DCIM/", "DCIM/../", "DCIM//Camera/", "/DCIM/", "A\\B/"}) {
            assertEquals("", MediaPaths.parentId("external_primary", path));
        }
        assertEquals("", MediaPaths.parentId("primary:other", "Pictures/"));
    }

    @Test public void neverRescansArchiveHiddenOrPrivateDirectories() {
        for (String parent : new String[]{"primary:DCIM/Camera/分类/iPhone 15",
                "primary:Pictures/分类", "primary:Android/data/app", "primary:Android/obb/app",
                "primary:Pictures/.thumbnails", "primary:App/cache/photos"}) {
            assertTrue(parent, MediaPaths.excluded(parent));
        }
        assertFalse(MediaPaths.excluded("primary:Pictures/旅行"));
        assertFalse(MediaPaths.excluded("primary:DCIM/Camera"));
        assertFalse(MediaPaths.excluded("primary:Pictures/分类前"));
        assertFalse(MediaPaths.excluded("primary:Android/media/app/Images"));
    }

    @Test public void permissionMatchingUsesPathBoundariesAndVolumeIdentity() {
        assertTrue(MediaPaths.contains("primary:DCIM", "primary:DCIM/Camera/a.jpg"));
        assertTrue(MediaPaths.contains("1234-ABCD:Pictures", "1234-abcd:Pictures/a.jpg"));
        assertFalse(MediaPaths.contains("primary:DCIM", "primary:DCIM-old/a.jpg"));
        assertFalse(MediaPaths.contains("primary:Pictures", "1234-abcd:Pictures/a.jpg"));
        assertEquals("primary:DCIM/Camera", MediaPaths.parent("primary:DCIM/Camera/a.jpg"));
        assertEquals("primary:", MediaPaths.parent("primary:a.jpg"));
    }

    @Test public void permissionPromptsShowReadableLocation() {
        assertEquals("内部存储/Pictures/高中", MediaPaths.display("primary:Pictures/高中"));
        assertEquals("内部存储", MediaPaths.display("primary:"));
        assertEquals("SD 卡/DCIM/Camera", MediaPaths.display("1234-ABCD:DCIM/Camera"));
        assertEquals("", MediaPaths.display(""));
    }

    @Test public void broadGrantSuggestionUsesOneDcimOrPicturesTree() {
        assertEquals("primary:DCIM", MediaPaths.coveringRoot(java.util.List.of(
                "primary:DCIM/Camera", "primary:DCIM/Screenshots", "primary:DCIM")));
        assertEquals("primary:Pictures", MediaPaths.coveringRoot(java.util.List.of(
                "primary:Pictures/旅行", "primary:Pictures/家庭")));
        assertEquals("", MediaPaths.coveringRoot(java.util.List.of(
                "primary:DCIM/Camera", "primary:Pictures/旅行")));
        assertEquals("", MediaPaths.coveringRoot(java.util.List.of("primary:DCIM/Camera")));
        assertEquals("", MediaPaths.coveringRoot(java.util.List.of(
                "primary:DCIM/Camera", "1234-abcd:DCIM/Camera")));
    }

    @Test public void archivedFolderIsSeparatedFromPrivateFolders() {
        String archived = "primary:Download/vivo办公套件/2026/Photos/分类/vivo X200/2026-01/照片";
        assertTrue(MediaPaths.archived(archived));
        assertFalse(MediaPaths.hidden(archived));
        assertTrue(MediaPaths.excluded(archived));
        assertFalse(MediaPaths.archived("primary:Pictures/旅行"));
        assertTrue(MediaPaths.hidden("primary:Android/data/app"));
        assertFalse(MediaPaths.hidden("primary:Pictures/旅行"));
    }

    @Test public void reclassifyingArchivedFilesNeverNestsAnotherArchiveFolder() {
        assertEquals("primary:Download/vivo办公套件/2026/Photos",
                MediaPaths.archiveBase(
                        "primary:Download/vivo办公套件/2026/Photos/分类/vivo X200/2026-01/照片"));
        assertEquals("primary:Pictures", MediaPaths.archiveBase("primary:Pictures/分类"));
        assertEquals("primary:", MediaPaths.archiveBase("primary:分类"));
        // A path without 分类 keeps its own folder as the base.
        assertEquals("primary:Pictures/旅行", MediaPaths.archiveBase("primary:Pictures/旅行"));
        for (String parent : new String[]{
                "primary:Download/A/分类/X/2026-01/照片", "primary:Pictures/分类", "primary:分类"}) {
            String base = MediaPaths.archiveBase(parent) + "/" + MediaPaths.ARCHIVE;
            assertEquals(base, 1, base.split(MediaPaths.ARCHIVE, -1).length - 1);
        }
    }

    @Test public void cameraMembershipIsExactAndCaseInsensitive() {
        assertTrue(MediaPaths.inCamera("primary:DCIM/Camera"));
        assertTrue(MediaPaths.inCamera("primary:dcim/camera"));
        assertFalse(MediaPaths.inCamera("primary:DCIM/Camera/分类"));
        assertFalse(MediaPaths.inCamera("primary:DCIM"));
        assertEquals("DCIM/Camera", MediaPaths.relative("primary:DCIM/Camera"));
    }

    @Test public void managedMediaIsDistinguishableFromPersonalAlbums() {
        assertTrue(MediaPaths.managed("primary:tencent/MicroMsg"));
        assertTrue(MediaPaths.managed("primary:Pictures/Telegram"));
        assertTrue(MediaPaths.managed("primary:Android/media/app/Images"));
        assertFalse(MediaPaths.managed("primary:Pictures/旅行"));
    }

    @Test public void onlySystemLockedFoldersAreTreatedAsUngrantable() {
        // 选择器对这些目录禁用“使用此文件夹”，永远授权不了；空位置同样无从授权。
        for (String parent : new String[]{"", null, "primary:", "primary:/", "1234-ABCD:",
                "primary:Download", "primary:download/", "primary:Android/data/com.app",
                "primary:Android/obb/com.app"}) {
            assertTrue(String.valueOf(parent), MediaPaths.ungrantable(parent));
        }
        for (String parent : new String[]{"primary:DCIM/Camera", "primary:Download/A",
                "primary:Pictures", "primary:Android/media/com.app/Images",
                "primary:DCIM/Camera/分类/iPhone 15", "primary:分类"}) {
            assertFalse(parent, MediaPaths.ungrantable(parent));
        }
    }

    @Test public void lockedFoldersReadAsRootOfTheirLevel() {
        assertEquals("内部存储（根目录）", MediaPaths.lockedName("primary:"));
        assertEquals("内部存储/Download（根目录）", MediaPaths.lockedName("primary:Download"));
        assertEquals("SD 卡（根目录）", MediaPaths.lockedName("1234-ABCD:"));
    }
}
