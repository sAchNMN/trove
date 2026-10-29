package app.devicesorter;

import android.content.ContentResolver;
import android.net.Uri;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.quicktime.QuickTimeMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import java.io.BufferedInputStream;
import java.io.InputStream;

final class Metadata {
    static String read(ContentResolver resolver, Uri uri, boolean video) throws Exception {
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new java.io.IOException("无法打开文件");
            InputStream stream = new BufferedInputStream(raw);
            com.drew.metadata.Metadata metadata = video
                    ? QuickTimeMetadataReader.readMetadata(stream)
                    : ImageMetadataReader.readMetadata(stream);
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null) {
                String model = Naming.device(exif.getString(ExifIFD0Directory.TAG_MAKE),
                        exif.getString(ExifIFD0Directory.TAG_MODEL));
                if (model != null) return model;
            }
            QuickTimeMetadataDirectory qt = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
            return qt == null ? null : Naming.device(qt.getString(QuickTimeMetadataDirectory.TAG_MAKE),
                    qt.getString(QuickTimeMetadataDirectory.TAG_MODEL));
        }
    }
}
