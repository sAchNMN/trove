package app.devicesorter;

import java.util.Collection;
import java.util.Locale;

final class MediaPaths {
    static final String ARCHIVE = "分类";
    static final String CAMERA_PATH = "DCIM/Camera";
    static final String CAMERA_ID = "primary:" + CAMERA_PATH;

    static String parent(String documentId) {
        int colon = documentId.indexOf(':');
        int slash = documentId.lastIndexOf('/');
        if (colon < 1) throw new IllegalArgumentException("无效存储路径");
        return slash > colon ? documentId.substring(0, slash) : documentId.substring(0, colon + 1);
    }

    static boolean contains(String tree, String document) {
        int colon = tree.indexOf(':');
        int other = document.indexOf(':');
        if (colon < 1 || other < 1 || !tree.substring(0, colon).equalsIgnoreCase(document.substring(0, other))) {
            return false;
        }
        String root = tree.substring(colon + 1), path = document.substring(other + 1);
        return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
    }

    /** Human-readable path without the volume prefix, e.g. primary:DCIM/Camera → DCIM/Camera. */
    static String relative(String documentId) {
        if (documentId == null) return "";
        int colon = documentId.indexOf(':');
        return colon < 0 ? documentId : documentId.substring(colon + 1);
    }

    static boolean inCamera(String parentId) {
        return relative(parentId).equalsIgnoreCase(CAMERA_PATH);
    }

    /**
     * A single SAF tree grant on DCIM or Pictures already covers every descendant.
     * When several missing folders share one of those roots, authorize it once.
     */
    static String coveringRoot(Collection<String> ids) {
        if (ids == null || ids.size() < 2) return "";
        String volume = "";
        boolean dcim = true, pictures = true;
        for (String id : ids) {
            int colon = id == null ? -1 : id.indexOf(':');
            if (colon < 1) return "";
            String itemVolume = id.substring(0, colon);
            if (volume.isEmpty()) volume = itemVolume;
            else if (!volume.equalsIgnoreCase(itemVolume)) return "";
            String path = id.substring(colon + 1);
            dcim &= path.equals("DCIM") || path.startsWith("DCIM/");
            pictures &= path.equals("Pictures") || path.startsWith("Pictures/");
        }
        if (!volume.isEmpty() && dcim) return volume + ":DCIM";
        if (!volume.isEmpty() && pictures) return volume + ":Pictures";
        return "";
    }

    static boolean excluded(String parentId) {
        return hidden(parentId) || archived(parentId);
    }

    /**
     * Android 11 起，系统选择器不会把卷根目录、Download 本身、Android/data 和 Android/obb
     * 授权给应用（“使用此文件夹”是灰的）。把这些目录当成“还没授权”只会把用户带进一个
     * 永远点不通的选择器，所以它们必须走“跳过或改为复制”，而不是授权。
     */
    static boolean ungrantable(String parentId) {
        int colon = parentId == null ? -1 : parentId.indexOf(':');
        if (colon < 1) return true;
        String path = parentId.substring(colon + 1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.equals("download")
                || lower.equals("android/data") || lower.startsWith("android/data/")
                || lower.equals("android/obb") || lower.startsWith("android/obb/");
    }

    /** Anything the scan must never show: system app data, caches, dot folders. */
    static boolean hidden(String parentId) {
        int colon = parentId.indexOf(':');
        if (colon < 1) return true;
        String path = parentId.substring(colon + 1);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.equals("android/data") || lowerPath.startsWith("android/data/")
                || lowerPath.equals("android/obb") || lowerPath.startsWith("android/obb/")) return true;
        for (String component : path.split("/")) {
            String lower = component.toLowerCase(Locale.ROOT);
            if (component.startsWith(".") || lower.equals("cache") || lower.equals("thumbnails")) return true;
        }
        return false;
    }

    /** True when the path already sits inside this app's own 分类 archive. */
    static boolean archived(String parentId) {
        int colon = parentId.indexOf(':');
        if (colon < 1) return false;
        for (String component : parentId.substring(colon + 1).split("/")) {
            if (component.equals(ARCHIVE)) return true;
        }
        return false;
    }

    /**
     * The folder that owns the 分类 directory, e.g.
     * primary:Download/A/Photos/分类/相机/2021 → primary:Download/A/Photos.
     * Re-classifying an archived file replaces what is under 分类 instead of nesting
     * a second 分类 inside the first one.
     */
    static String archiveBase(String parentId) {
        int colon = parentId.indexOf(':');
        if (colon < 1) return parentId;
        StringBuilder base = new StringBuilder(parentId.substring(0, colon)).append(':');
        String[] parts = parentId.substring(colon + 1).split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(ARCHIVE)) break;
            if (i > 0) base.append('/');
            base.append(parts[i]);
        }
        return base.toString();
    }

    static boolean managed(String parentId) {
        String path = parentId.toLowerCase(Locale.ROOT);
        return path.contains(":android/media") || path.contains(":tencent/") || path.contains(":tencent")
                || path.contains("/tencent/") || path.contains("/com.") || path.contains(":com.")
                || path.contains("whatsapp") || path.contains("telegram");
    }

    /** 把提供器文档 ID 变成用户能认出的路径，例如 primary:Pictures/高中 → 内部存储/Pictures/高中。 */
    static String display(String documentId) {
        if (documentId == null) return "";
        int colon = documentId.indexOf(':');
        if (colon < 1) return documentId;
        String volume = documentId.substring(0, colon);
        String path = documentId.substring(colon + 1);
        String name = volume.equalsIgnoreCase("primary") ? "内部存储" : "SD 卡";
        return path.isEmpty() ? name : name + "/" + path;
    }

    /** 系统不授予的目录，要说清它是它那一层的根，例如 内部存储（根目录）。 */
    static String lockedName(String parentId) {
        return display(parentId) + "（根目录）";
    }

    static String parentId(String volume, String relative) {
        if (volume == null || volume.isEmpty() || relative == null) return "";
        if (!volume.matches("[A-Za-z0-9_-]+")) return "";
        String path = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
        for (String part : path.split("/", -1)) {
            if (part.equals(".") || part.equals("..") || part.contains(":") || part.contains("\\")
                    || part.indexOf('\0') >= 0 || (part.isEmpty() && !path.isEmpty())) return "";
        }
        return (volume.equals("external_primary") ? "primary" : volume) + ":" + path;
    }
}
