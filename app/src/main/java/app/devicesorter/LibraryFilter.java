package app.devicesorter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure in-memory filter used by the media browser. */
final class LibraryFilter {
    private LibraryFilter() {}

    static final class Query {
        String keyword = "";
        String type = "all";
        String model = "";
        String parentId = "";
        long minSize = 0;
        long maxSize = Long.MAX_VALUE;
        String from = "";
        String to = "";
        boolean outsideCamera;

        void validate() {
            if (!type.equals("all") && !type.equals("photo") && !type.equals("video")) {
                throw new IllegalArgumentException("无效类型");
            }
            if (minSize < 0 || maxSize < 0 || minSize > maxSize) {
                throw new IllegalArgumentException("文件大小范围无效");
            }
            if (!from.isEmpty() && !date(from)) throw new IllegalArgumentException("起始日期应为 YYYY-MM-DD");
            if (!to.isEmpty() && !date(to)) throw new IllegalArgumentException("结束日期应为 YYYY-MM-DD");
            if (!from.isEmpty() && !to.isEmpty() && from.compareTo(to) > 0) {
                throw new IllegalArgumentException("起始日期不能晚于结束日期");
            }
        }

        boolean matches(MediaItem item) {
            if (type.equals("photo") && item.video) return false;
            if (type.equals("video") && !item.video) return false;
            if (!model.isEmpty() && !model.equals(item.model == null ? "" : item.model)) return false;
            if (!parentId.isEmpty() && !parentId.equals(item.parentId)) return false;
            if (item.size >= 0 && (item.size < minSize || item.size > maxSize)) return false;
            if (outsideCamera && MediaPaths.inCamera(item.parentId)) return false;
            if (!from.isEmpty() || !to.isEmpty()) {
                if (item.capturedAt.length() < 10) return false;
                String day = item.capturedAt.substring(0, 10);
                if (!from.isEmpty() && day.compareTo(from) < 0) return false;
                if (!to.isEmpty() && day.compareTo(to) > 0) return false;
            }
            if (!keyword.isEmpty()) {
                String haystack = (item.name + "\n" + item.path + "\n" + MediaPaths.display(item.parentId)
                        + "\n" + (item.model == null ? "" : item.model) + "\n" + item.parameters
                        + "\n" + item.width + "x" + item.height + "\n" + item.duration
                        + "\n" + item.size + "\n" + (item.video ? "视频" : "照片"))
                        .toLowerCase(Locale.ROOT);
                for (String token : keyword.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
                    if (!token.isEmpty() && !haystack.contains(token)) return false;
                }
            }
            return true;
        }
    }

    static boolean date(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            java.time.LocalDate.parse(value);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    static long megabytes(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return -1;
        try {
            double number = Double.parseDouble(text);
            if (number < 0 || Double.isNaN(number) || Double.isInfinite(number)) {
                throw new NumberFormatException();
            }
            return Math.round(number * 1024 * 1024);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("请填写有效的 MB 数值");
        }
    }

    static List<MediaItem> apply(List<MediaItem> items, Query query) {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : items) if (query.matches(item)) result.add(item);
        return result;
    }

    static List<String> models(List<MediaItem> items) {
        Set<String> result = new LinkedHashSet<>();
        for (MediaItem item : items) if (item.model != null && !item.model.isEmpty()) result.add(item.model);
        List<String> sorted = new ArrayList<>(result);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** Distinct source folders, sorted by the readable path rather than the raw document id. */
    static List<String> folders(List<MediaItem> items) {
        Set<String> result = new LinkedHashSet<>();
        for (MediaItem item : items) if (!item.parentId.isEmpty()) result.add(item.parentId);
        List<String> sorted = new ArrayList<>(result);
        sorted.sort((a, b) -> MediaPaths.display(a).compareToIgnoreCase(MediaPaths.display(b)));
        return sorted;
    }
}