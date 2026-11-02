package app.devicesorter;

import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataTest {
    @Test public void strictDatesPreserveLocalTimeAndOffset() {
        assertEquals("2026-10-29T23:50:12+08:00", Metadata.date("2026:10:29 23:50:12", "+08:00"));
        assertEquals("2026-10-29T23:50:12", Metadata.date("2026:10:29 23:50:12", null));
        assertEquals("2026-10-29T23:50:12-07:00", Metadata.date("2026-10-29T23:50:12-07:00", null));
        assertEquals("2026-10-29T23:50:12+08:00", Metadata.date("2026-10-29T23:50:12+0800", null));
        for (String date : new String[]{"2026:02:30 12:00:00", "0000:00:00 00:00:00", "unknown", "2026-10", "0000-01-01T00:00:00Z"}) {
            assertEquals(date, "", Metadata.date(date, null));
        }
        assertEquals("", Metadata.date("2026:10:29 23:50:12", "+99:00"));
    }

    @Test public void imageOriginalDateAndModelAreIndependent() {
        com.drew.metadata.Metadata data = new com.drew.metadata.Metadata();
        ExifIFD0Directory exif = new ExifIFD0Directory();
        exif.setString(ExifIFD0Directory.TAG_MAKE, "samsung");
        exif.setString(ExifIFD0Directory.TAG_MODEL, "SM-S928B");
        exif.setString(ExifIFD0Directory.TAG_DATETIME, "2025:01:01 00:00:00");
        data.addDirectory(exif);
        Metadata.Info info = Metadata.extract(data);
        assertEquals("samsung SM-S928B", info.model());
        assertEquals("", info.capturedAt());
        ExifSubIFDDirectory original = new ExifSubIFDDirectory();
        original.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2024:02:29 21:30:00");
        original.setString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL, "+08:00");
        data.addDirectory(original);
        assertEquals("2024-02-29T21:30:00+08:00", Metadata.extract(data).capturedAt());
    }

    @Test public void xmpSupportsDeviceAndOriginalDate() throws Exception {
        com.drew.metadata.Metadata data = new com.drew.metadata.Metadata();
        XMPMeta xmp = XMPMetaFactory.create();
        xmp.setProperty("http://ns.adobe.com/tiff/1.0/", "Make", "Sony");
        xmp.setProperty("http://ns.adobe.com/tiff/1.0/", "Model", "ILCE-7M4");
        xmp.setProperty("http://ns.adobe.com/exif/1.0/", "DateTimeOriginal", "2026-10-29T12:00:00+08:00");
        XmpDirectory directory = new XmpDirectory();
        directory.setXMPMeta(xmp);
        data.addDirectory(directory);
        assertEquals("Sony ILCE-7M4", Metadata.extract(data).model());
        assertEquals("2026-10-29T12:00:00+08:00", Metadata.extract(data).capturedAt());
    }

    @Test public void contradictoryMetadataRequiresConfirmation() {
        com.drew.metadata.Metadata data = new com.drew.metadata.Metadata();
        ExifIFD0Directory exif = new ExifIFD0Directory();
        exif.setString(ExifIFD0Directory.TAG_MODEL, "iPhone 15");
        data.addDirectory(exif);
        QuickTimeMetadataDirectory qt = new QuickTimeMetadataDirectory();
        qt.setString(QuickTimeMetadataDirectory.TAG_MODEL, "iPhone 16");
        qt.setString(QuickTimeMetadataDirectory.TAG_CREATION_DATE, "2026-10-29T12:00:00+08:00");
        data.addDirectory(qt);
        ExifSubIFDDirectory original = new ExifSubIFDDirectory();
        original.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2026:10:28 12:00:00");
        data.addDirectory(original);
        Metadata.Info info = Metadata.extract(data);
        assertNull(info.model());
        assertEquals("", info.capturedAt());
        assertTrue(info.issue().contains("冲突"));
    }

    @Test public void emptyMetadataNeverGuessesDeviceOrDate() {
        Metadata.Info info = Metadata.extract(new com.drew.metadata.Metadata());
        assertNull(info.model());
        assertEquals("", info.capturedAt());
    }
}
