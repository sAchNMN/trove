package app.devicesorter;

import android.content.ContentResolver;
import android.net.Uri;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.quicktime.QuickTimeMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.drew.metadata.Directory;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.Set;

final class Metadata {
    record Info(String model, String capturedAt, String source, String parameters, String issue) {}

    static String read(ContentResolver resolver, Uri uri, boolean video) throws Exception {
        return inspect(resolver, uri, video).model();
    }

    static Info inspect(ContentResolver resolver, Uri uri, boolean video) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new java.io.IOException("无法打开文件");
            InputStream stream = new BufferedInputStream(raw);
            com.drew.metadata.Metadata metadata = video
                    ? QuickTimeMetadataReader.readMetadata(stream)
                    : ImageMetadataReader.readMetadata(stream);
            return extract(metadata);
        }
    }

    static Info extract(com.drew.metadata.Metadata metadata) {
        Set<String> models = new LinkedHashSet<>();
        Set<String> dates = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        StringBuilder parameters = new StringBuilder();
        for (Directory directory : metadata.getDirectories()) {
            if (directory instanceof ExifIFD0Directory) {
                add(models, Naming.device(directory.getString(ExifIFD0Directory.TAG_MAKE),
                        directory.getString(ExifIFD0Directory.TAG_MODEL)), sources, "EXIF 设备");
                detail(parameters, directory, ExifIFD0Directory.TAG_MAKE, "EXIF 厂商");
                detail(parameters, directory, ExifIFD0Directory.TAG_MODEL, "EXIF 型号");
            }
            if (directory instanceof ExifSubIFDDirectory) {
                add(dates, date(directory.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL),
                        directory.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL)), sources, "EXIF 原始拍摄时间");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "EXIF 原始时间");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "EXIF 时区");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_LENS_MODEL, "镜头");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_EXPOSURE_TIME, "快门");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_FNUMBER, "光圈");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_ISO_EQUIVALENT, "ISO");
                detail(parameters, directory, ExifSubIFDDirectory.TAG_FOCAL_LENGTH, "焦距");
            }
            if (directory instanceof QuickTimeMetadataDirectory) {
                add(models, Naming.device(directory.getString(QuickTimeMetadataDirectory.TAG_MAKE),
                        directory.getString(QuickTimeMetadataDirectory.TAG_MODEL)), sources, "QuickTime 设备");
                add(dates, date(directory.getString(QuickTimeMetadataDirectory.TAG_CREATION_DATE), null),
                        sources, "QuickTime 拍摄日期");
                detail(parameters, directory, QuickTimeMetadataDirectory.TAG_MODEL, "QuickTime 型号");
                detail(parameters, directory, QuickTimeMetadataDirectory.TAG_CREATION_DATE, "QuickTime 原始时间");
            }
            if (directory instanceof XmpDirectory) {
                com.adobe.internal.xmp.XMPMeta xmp = ((XmpDirectory) directory).getXMPMeta();
                try {
                    add(models, Naming.device(xmp.getPropertyString("http://ns.adobe.com/tiff/1.0/", "Make"),
                            xmp.getPropertyString("http://ns.adobe.com/tiff/1.0/", "Model")), sources, "XMP 设备");
                    add(dates, date(xmp.getPropertyString("http://ns.adobe.com/exif/1.0/",
                            "DateTimeOriginal"), null), sources, "XMP 原始拍摄时间");
                    String model = xmp.getPropertyString("http://ns.adobe.com/tiff/1.0/", "Model");
                    String original = xmp.getPropertyString("http://ns.adobe.com/exif/1.0/", "DateTimeOriginal");
                    if (model != null) parameters.append("XMP 型号：").append(model).append('\n');
                    if (original != null) parameters.append("XMP 原始时间：").append(original).append('\n');
                } catch (com.adobe.internal.xmp.XMPException ignored) {
                    parameters.append("XMP 字段无法读取\n");
                }
            }
        }
        // Compare recorded local wall time, never shift an album date to the phone's timezone.
        long localDates = dates.stream().map(value -> value.substring(0, 19)).distinct().count();
        Set<String> offsets = new LinkedHashSet<>();
        for (String value : dates) if (value.length() > 19) offsets.add(value.substring(19));
        boolean dateConflict = localDates > 1 || offsets.size() > 1;
        String captured = dates.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
        String issue = (models.size() > 1 ? "设备字段冲突；" : models.isEmpty() ? "未找到设备型号；" : "")
                + (dateConflict ? "拍摄时间字段冲突" : dates.isEmpty() ? "未找到有效原始拍摄时间" : "");
        return new Info(models.size() == 1 ? models.iterator().next() : null,
                dateConflict ? "" : captured, String.join("、", sources), parameters.toString(), issue);
    }

    private static void add(Set<String> values, String value, Set<String> sources, String source) {
        if (value != null && !value.isEmpty()) { values.add(value); sources.add(source); }
    }

    private static void detail(StringBuilder text, Directory directory, int tag, String label) {
        String value = directory.getDescription(tag);
        if (value != null && !value.isEmpty()) text.append(label).append("：").append(value).append('\n');
    }

    static String date(String raw, String offset) {
        if (raw == null) return "";
        String value = raw.replace("\u0000", "").trim();
        try {
            LocalDateTime local;
            if (value.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
                        .withResolverStyle(ResolverStyle.STRICT));
            } else {
                try {
                    OffsetDateTime parsed = OffsetDateTime.parse(value);
                    if (parsed.getYear() < 1) return "";
                    return parsed.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (java.time.DateTimeException ignored) {
                    try {
                        OffsetDateTime parsed = OffsetDateTime.parse(value,
                                new java.time.format.DateTimeFormatterBuilder()
                                        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                        .appendOffset("+HHMM", "Z").toFormatter()
                                        .withResolverStyle(ResolverStyle.STRICT));
                        if (parsed.getYear() < 1) return "";
                        return parsed.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    } catch (java.time.DateTimeException noOffset) {
                        local = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    }
                }
            }
            if (local.getYear() < 1) return "";
            String result = local.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
            if (offset != null && !offset.trim().isEmpty()) {
                result += ZoneOffset.of(offset.trim()).getId();
            }
            return result;
        } catch (java.time.DateTimeException e) { return ""; }
    }
}
