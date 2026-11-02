package app.devicesorter;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class NativeMoveTest {
    static class Fake implements NativeMove.Files {
        byte[] source = "original photo bytes".getBytes(StandardCharsets.UTF_8);
        byte[] target;
        boolean supported = true, collision, cancelled, journalFailure, moved;
        final List<String> events = new ArrayList<>();
        @Override public InputStream readSource() { return new ByteArrayInputStream(source); }
        @Override public InputStream readTarget() { return new ByteArrayInputStream(target); }
        @Override public long sourceSize() { return source.length; }
        @Override public long sourceModified() { return 123; }
        @Override public boolean supported() { return supported; }
        @Override public boolean targetExists() { return collision; }
        @Override public void move() throws IOException {
            assertEquals(List.of("NATIVE_MOVE_PENDING"), events);
            moved = true;
            target = source.clone();
        }
        @Override public void record(String status, String hash) throws IOException {
            if (journalFailure) throw new IOException("journal full");
            assertEquals(64, hash.length());
            events.add(status);
        }
        @Override public boolean cancelled() { return cancelled; }
    }

    @Test public void supportedMoveIsJournaledAndVerified() throws Exception {
        Fake files = new Fake();
        assertEquals(64, NativeMove.run(files, files.source.length, 123).length());
        assertTrue(files.moved);
        assertArrayEquals(files.source, files.target);
        assertEquals(List.of("NATIVE_MOVE_PENDING", "NATIVE_MOVE_RETURNED", "NATIVE_MOVED"), files.events);
    }

    @Test public void unsupportedMoveNeverFallsBackToCopyOrDelete() {
        Fake files = new Fake();
        files.supported = false;
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
        assertNull(files.target);
    }

    @Test public void preexistingDestinationNeverGetsOverwritten() {
        Fake files = new Fake();
        files.collision = true;
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
    }

    @Test public void failedIntentJournalPreventsMove() {
        Fake files = new Fake();
        files.journalFailure = true;
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
    }

    @Test public void sameSizeMutationDuringIntentJournalPreventsMove() {
        Fake files = new Fake() {
            @Override public void record(String status, String hash) throws IOException {
                super.record(status, hash);
                source[0] ^= 1;
            }
        };
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
    }

    @Test public void cancellationDuringIntentJournalPreventsMove() {
        Fake files = new Fake() {
            @Override public void record(String status, String hash) throws IOException {
                super.record(status, hash);
                cancelled = true;
            }
        };
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
    }

    @Test public void collisionAppearingDuringJournalPreventsMove() {
        Fake files = new Fake() {
            @Override public void record(String status, String hash) throws IOException {
                super.record(status, hash);
                collision = true;
            }
        };
        assertThrows(IOException.class, () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.moved);
    }

    @Test public void providerThrowsAfterMoveIsUncertainNeverRetried() {
        Fake files = new Fake() {
            @Override public void move() throws IOException {
                super.move();
                throw new IOException("provider disconnected after move");
            }
        };
        assertThrows(NativeMove.UncertainException.class,
                () -> NativeMove.run(files, files.source.length, 123));
        assertTrue(files.moved);
        assertArrayEquals(files.source, files.target);
        assertEquals(List.of("NATIVE_MOVE_PENDING"), files.events);
    }

    @Test public void postMoveCorruptionStopsInsteadOfAttemptingRollbackOrDeletion() {
        Fake files = new Fake() {
            @Override public void move() throws IOException {
                super.move();
                target[0] ^= 1;
            }
        };
        assertThrows(NativeMove.UncertainException.class,
                () -> NativeMove.run(files, files.source.length, 123));
        assertFalse(files.events.contains("NATIVE_MOVED"));
    }

    @Test public void cancellationAfterMoveStillFinishesVerification() throws Exception {
        Fake files = new Fake() {
            @Override public void move() throws IOException {
                super.move();
                cancelled = true;
            }
        };
        NativeMove.run(files, files.source.length, 123);
        assertTrue(files.events.contains("NATIVE_MOVED"));
    }

    @Test public void staleScanCannotMove() {
        Fake files = new Fake();
        assertThrows(IOException.class, () -> NativeMove.run(files, 999, 123));
        assertFalse(files.moved);
    }
}
