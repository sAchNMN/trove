package app.devicesorter;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TransferTest {
    static class FakeFiles implements Transfer.Files {
        byte[] source = "original media bytes".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream target;
        boolean deleted, cleaned, corruptTarget, failWrite, failJournal, failDelete, cancel;
        boolean mutateBeforeDelete, mutateDuringCopy;
        int sourceReads;
        long modified = 123;
        List<String> journal = new ArrayList<>();

        @Override public InputStream readSource() {
            sourceReads++;
            byte[] data = source.clone();
            if (sourceReads > 1 && mutateBeforeDelete) data[0] ^= 1;
            return new ByteArrayInputStream(data);
        }
        @Override public InputStream readTarget() {
            byte[] data = target.toByteArray();
            if (corruptTarget) data[0] ^= 1;
            if (mutateDuringCopy) modified++;
            return new ByteArrayInputStream(data);
        }
        @Override public OutputStream createTarget() {
            target = new ByteArrayOutputStream();
            return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    if (failWrite) throw new IOException("disk full");
                    target.write(b);
                }
            };
        }
        @Override public long sourceSize() { return source.length; }
        @Override public long sourceModified() { return modified; }
        @Override public void deleteSource() throws IOException {
            if (failDelete) throw new IOException("delete denied");
            assertEquals("DELETE_PENDING", journal.get(journal.size() - 1));
            deleted = true;
        }
        @Override public void deletePartialTarget() { cleaned = true; target = null; }
        @Override public void record(String status, String hash) throws IOException {
            if (failJournal) throw new IOException("journal full");
            journal.add(status);
            assertEquals(64, hash.length());
        }
        @Override public boolean cancelled() { return cancel; }
    }

    @Test public void copyKeepsSourceAndVerifiesBytes() throws Exception {
        FakeFiles files = new FakeFiles();
        assertEquals("COPIED", Transfer.run(files, false, files.source.length, 123).status());
        assertArrayEquals(files.source, files.target.toByteArray());
        assertFalse(files.deleted);
        assertEquals(List.of("VERIFIED_COPY"), files.journal);
    }

    @Test public void moveChecksBothFilesBeforeDeleting() throws Exception {
        FakeFiles files = new FakeFiles();
        assertEquals("MOVED", Transfer.run(files, true, files.source.length, 123).status());
        assertTrue(files.deleted);
        assertEquals(2, files.sourceReads);
        assertEquals(List.of("VERIFIED_COPY", "DELETE_PENDING"), files.journal);
    }

    @Test public void corruptCopyNeverDeletesOriginal() {
        FakeFiles files = new FakeFiles(); files.corruptTarget = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertTrue(files.cleaned);
    }

    @Test public void outOfSpaceNeverDeletesOriginal() {
        FakeFiles files = new FakeFiles(); files.failWrite = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertTrue(files.cleaned);
    }

    @Test public void missingJournalPreventsDeletionAndKeepsVerifiedCopy() {
        FakeFiles files = new FakeFiles(); files.failJournal = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertNotNull(files.target);
        assertFalse(files.cleaned);
    }

    @Test public void deletionFailureKeepsBothCopies() {
        FakeFiles files = new FakeFiles(); files.failDelete = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertArrayEquals(files.source, files.target.toByteArray());
    }

    @Test public void stalePreviewCannotMoveChangedFile() {
        FakeFiles files = new FakeFiles();
        assertThrows(IOException.class, () -> Transfer.run(files, true, 999, 123));
        assertFalse(files.deleted);
        assertNull(files.target);
    }

    @Test public void changedTimestampDuringCopyPreservesOriginal() {
        FakeFiles files = new FakeFiles(); files.mutateDuringCopy = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertTrue(files.cleaned);
    }

    @Test public void sameSizeContentChangeIsDetectedBeforeDeletion() {
        FakeFiles files = new FakeFiles(); files.mutateBeforeDelete = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
        assertNotNull(files.target);
    }

    @Test public void cancellationNeverDeletesSource() {
        FakeFiles files = new FakeFiles(); files.cancel = true;
        assertThrows(IOException.class, () -> Transfer.run(files, true, files.source.length, 123));
        assertFalse(files.deleted);
    }

    @Test public void unknownLengthStillRequiresMatchingHashes() throws Exception {
        FakeFiles files = new FakeFiles() {
            @Override public long sourceSize() { return -1; }
        };
        assertEquals("MOVED", Transfer.run(files, true, -1, 123).status());
        assertTrue(files.deleted);
    }
}
