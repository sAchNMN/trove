package app.devicesorter;

import org.junit.Test;
import static org.junit.Assert.*;

public class NamingTest {
    @Test public void familiarModelNamesAreKept() {
        assertEquals("iPhone 15", Naming.device("Apple", "iPhone 15"));
        assertEquals("Canon EOS R5", Naming.device("Canon", "Canon EOS R5"));
        assertEquals("vivo V2415", Naming.device("vivo", "V2415"));
    }

    @Test public void emptyModelsAreNotGuessedFromManufacturer() {
        assertNull(Naming.device("vivo", null));
        assertNull(Naming.device("Canon", " \u0000 "));
        assertNull(Naming.device("", "unknown"));
    }

    @Test public void unsafeNamesCannotEscapeDestination() {
        assertFalse(Naming.folder("../a/b").contains("/"));
        assertFalse(Naming.folder("..").equals(".."));
        assertNotEquals(Naming.folder("a/b"), Naming.folder("a\\b"));
        assertTrue(Naming.folder("x".repeat(200)).length() < 110);
    }

    @Test public void conflictsPreserveExtension() {
        assertEquals("IMG (1).jpg", Naming.collisionName("IMG.jpg", 1));
        assertEquals("video (2)", Naming.collisionName("video", 2));
        assertEquals("a.b (1).mov", Naming.collisionName("a.b.mov", 1));
    }

    @Test public void directoryBoundariesAreNotStringPrefixes() {
        assertTrue(Naming.overlaps("primary:DCIM", "primary:DCIM/Camera"));
        assertTrue(Naming.overlaps("primary:Pictures", "primary:Pictures"));
        assertFalse(Naming.overlaps("primary:DCIM", "primary:DCIM-old"));
        assertFalse(Naming.overlaps("primary:A", "1234:A"));
    }

    @Test public void volumeRootsOverlapAllDirectoriesOnTheSameVolume() {
        assertTrue(Naming.overlaps("primary:", "primary:DCIM"));
        assertTrue(Naming.overlaps("primary:DCIM/Camera", "primary:"));
        assertTrue(Naming.overlaps("1234-ABCD:", "1234-ABCD:Pictures"));
        assertTrue(Naming.overlaps("1234-ABCD:Pictures", "1234-ABCD:"));
        assertTrue(Naming.overlaps("primary:", "primary:"));
        assertFalse(Naming.overlaps("primary:", "1234-ABCD:DCIM"));
        assertFalse(Naming.overlaps("primary:", "1234-ABCD:"));
    }
}
