package app.devicesorter;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

final class Classification {
    static final String DEFAULT = "device/month";
    static final String FLAT = "flat";
    static final String[] RULES = {DEFAULT, "device", "month", "day", FLAT};
    static final String[] TITLES = {"设备 / 拍摄年月（推荐）", "仅按设备", "仅按拍摄年月",
            "仅按拍摄日期", "不建子目录（直接放入目标目录）", "自定义层级"};
    static final String[] FIELDS = {"device", "year", "month", "day", "type"};
    static final String[] FIELD_TITLES = {"设备", "拍摄年份", "拍摄年月", "拍摄日期", "照片 / 视频"};

    static boolean valid(String rule) {
        if (FLAT.equals(rule)) return true;
        if (rule == null || rule.isEmpty()) return false;
        String[] parts = rule.split("/", -1);
        if (parts.length > 5) return false;
        List<String> used = new ArrayList<>();
        for (String part : parts) {
            if (!java.util.Arrays.asList(FIELDS).contains(part) || used.contains(part)) return false;
            used.add(part);
        }
        // A media type alone is not evidence of capture device or date.
        return used.contains("device") || used.contains("year") || used.contains("month") || used.contains("day");
    }

    static String folder(MediaItem item, String rule) {
        if (!valid(rule)) throw new IllegalArgumentException("无效分类策略");
        if (!item.manualFolder.isEmpty()) return manualPath(item.manualFolder);
        if (FLAT.equals(rule)) return "";
        if (!missing(item, rule).isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (String field : rule.split("/")) {
            switch (field) {
                case "device": parts.add(Naming.folder(item.model)); break;
                case "year": parts.add(item.capturedAt.substring(0, 4)); break;
                case "month": parts.add(item.capturedAt.substring(0, 7)); break;
                case "day": parts.add(item.capturedAt.substring(0, 10)); break;
                case "type": parts.add(item.video ? "视频" : "照片"); break;
                default: throw new IllegalArgumentException("无效分类层级");
            }
        }
        return String.join("/", parts);
    }

    static String missing(MediaItem item, String rule) {
        if (!valid(rule)) throw new IllegalArgumentException("无效分类策略");
        if (FLAT.equals(rule)) return "";
        List<String> missing = new ArrayList<>();
        List<String> fields = java.util.Arrays.asList(rule.split("/"));
        if (fields.contains("device") && item.model == null) missing.add("缺少可靠设备型号");
        if ((fields.contains("year") || fields.contains("month") || fields.contains("day"))
                && !validDate(item.capturedAt)) missing.add("缺少可靠拍摄时间");
        return String.join("；", missing);
    }

    private static boolean validDate(String date) {
        if (date == null || date.length() < 19) return false;
        try {
            LocalDateTime.parse(date.substring(0, 19));
            return !date.startsWith("0000");
        } catch (DateTimeParseException e) { return false; }
    }

    static String title(String rule) {
        if (FLAT.equals(rule)) return "不建子目录";
        List<String> parts = new ArrayList<>();
        for (String field : rule.split("/")) {
            int index = java.util.Arrays.asList(FIELDS).indexOf(field);
            if (index >= 0) parts.add(FIELD_TITLES[index]);
        }
        return String.join(" / ", parts);
    }

    static String manualPath(String value) {
        String[] parts = value.trim().split("/", -1);
        if (parts.length == 0 || parts.length > 5) throw new IllegalArgumentException("目录最多 5 层");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 90
                    || java.util.regex.Pattern.compile("[\\x00-\\x1f\\\\:*?\"<>|]").matcher(part).find()
                    || part.endsWith(".")) {
                throw new IllegalArgumentException("目录名不能为空，不可包含特殊字符或 .、.. 路径");
            }
            parts[i] = part;
        }
        return String.join("/", parts);
    }
}
