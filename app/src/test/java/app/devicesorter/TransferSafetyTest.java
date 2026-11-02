package app.devicesorter;

import org.junit.Test;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Uses only generated media under app/build/safety-simulation; never accesses ADB. */
public class TransferSafetyTest {
    enum Fault { NONE, IO, CANCEL, HALT }

    static class DiskFiles implements Transfer.Files {
        final Path root, source, folder, sentinel, journal;
        final byte[] original, untouched = "existing file must survive".getBytes(StandardCharsets.UTF_8);
        final List<String> trace = new ArrayList<>();
        Path target;
        Fault fault = Fault.NONE;
        String stopAt = "";
        int occurrence = 1, matches;
        boolean cancel;

        DiskFiles(Path root, int length) throws Exception {
            this.root = root;
            for (String name : List.of("DCIM/Camera", "Pictures/Screenshots", "Movies", "Download",
                    "Documents", "Music", "Recordings", "backups", "Android/media",
                    "tencent", ".VivoCamera", ".vivoFileRecycleBin")) {
                Files.createDirectories(root.resolve(name));
            }
            source = root.resolve("DCIM/Camera/IMG_0001.jpg");
            folder = root.resolve("Pictures/\u8bbe\u5907\u5f52\u6863/V2419A");
            Files.createDirectories(folder);
            sentinel = folder.resolve("IMG_0001.jpg");
            journal = root.resolve("operations.jsonl");
            original = new byte[length];
            new Random(2419).nextBytes(original);
            Files.write(source, original, StandardOpenOption.CREATE_NEW);
            Files.write(sentinel, untouched, StandardOpenOption.CREATE_NEW);
            Files.write(root.resolve("Documents/keep.txt"), "not media".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            Files.write(root.resolve("manifest.txt"), ("SYNTHETIC DATA ONLY\nsha256="
                    + hex(original) + "\nbytes=" + length + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
        }

        void hit(String event) throws IOException {
            trace.add(event);
            if (!event.equals(stopAt) || ++matches != occurrence) return;
            if (fault == Fault.HALT) Runtime.getRuntime().halt(73);
            if (fault == Fault.CANCEL) cancel = true;
            if (fault == Fault.IO) throw new IOException("Injected failure: " + event);
        }

        InputStream open(Path path, String label) throws IOException {
            hit(label + "-open");
            return new FilterInputStream(Files.newInputStream(path)) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    hit(label + "-read");
                    return super.read(b, off, len);
                }
                @Override public void close() throws IOException {
                    super.close();
                    hit(label + "-close");
                }
            };
        }

        @Override public InputStream readSource() throws IOException { return open(source, "source"); }
        @Override public InputStream readTarget() throws IOException { return open(target, "target"); }
        @Override public OutputStream createTarget() throws IOException {
            hit("target-create-before");
            target = folder.resolve(".devicesorter-test.partial");
            OutputStream stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
            return new FilterOutputStream(stream) {
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    hit("target-write-before");
                    out.write(b, off, len);
                    hit("target-write-after");
                }
                @Override public void flush() throws IOException {
                    hit("target-flush");
                    out.flush();
                }
                @Override public void close() throws IOException {
                    super.close();
                    hit("target-close");
                }
            };
        }
        @Override public void syncTarget() throws IOException {
            hit("target-sync-before");
            try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
                file.getFD().sync();
            }
            hit("target-sync-after");
        }
        @Override public long sourceSize() throws IOException {
            hit("source-size");
            return Files.size(source);
        }
        @Override public long sourceModified() throws IOException {
            hit("source-modified");
            return Files.getLastModifiedTime(source).toMillis();
        }
        @Override public void deleteSource() throws IOException {
            hit("source-delete-before");
            Files.delete(source);
            hit("source-delete-after");
        }
        @Override public void deletePartialTarget() throws IOException {
            if (target != null) Files.deleteIfExists(target);
        }
        @Override public void record(String status, String hash) throws IOException {
            hit("journal-" + status + "-before");
            if ("VERIFIED_COPY".equals(status)) {
                String name = source.getFileName().toString();
                int suffix = 0;
                while (Files.exists(folder.resolve(name))) {
                    name = Naming.collisionName(source.getFileName().toString(), ++suffix);
                }
                hit("rename-before");
                target = Files.move(target, folder.resolve(name));
                hit("rename-after");
            }
            byte[] line = (status + " " + hash + "\n").getBytes(StandardCharsets.UTF_8);
            try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
                file.seek(file.length());
                file.write(line);
                file.getFD().sync();
            }
            hit("journal-" + status + "-after");
        }
        @Override public boolean cancelled() { return cancel; }

        void assertPreserved() throws Exception {
            assertArrayEquals("Existing destination overwritten: " + root, untouched, Files.readAllBytes(sentinel));
            assertEquals("not media", new String(Files.readAllBytes(root.resolve("Documents/keep.txt")),
                    StandardCharsets.UTF_8));
            boolean intact = Files.exists(source) && Arrays.equals(original, Files.readAllBytes(source));
            try (var paths = Files.list(folder)) {
                for (Path file : paths.toList()) {
                    intact |= Arrays.equals(original, Files.readAllBytes(file));
                }
            }
            assertTrue("No complete copy remains: " + root + " trace=" + trace, intact);
        }
    }

    private static Path newRoot(String label) throws IOException {
        Path base = Path.of("build", "safety-simulation").toAbsolutePath().normalize();
        Files.createDirectories(base);
        return Files.createTempDirectory(base, label + "-");
    }

    private static String hex(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test public void everyIoAndCancellationBoundaryPreservesData() throws Exception {
        int cases = 0;
        for (boolean move : new boolean[] { false, true }) {
            DiskFiles baseline = new DiskFiles(newRoot("baseline"), 600_013);
            Transfer.run(baseline, move, baseline.original.length,
                    Files.getLastModifiedTime(baseline.source).toMillis());
            baseline.assertPreserved();
            List<String> events = baseline.trace;
            for (int index = 0; index < events.size(); index++) {
                String event = events.get(index);
                int occurrence = 0;
                for (int i = 0; i <= index; i++) if (events.get(i).equals(event)) occurrence++;
                for (Fault fault : new Fault[] { Fault.IO, Fault.CANCEL }) {
                    DiskFiles files = new DiskFiles(newRoot("boundary"), 600_013);
                    files.stopAt = event;
                    files.occurrence = occurrence;
                    files.fault = fault;
                    try {
                        Transfer.run(files, move, files.original.length,
                                Files.getLastModifiedTime(files.source).toMillis());
                    } catch (IOException expected) {
                        assertTrue(Files.exists(files.source) || event.equals("source-delete-after"));
                    }
                    assertTrue("Fault boundary was not reached: " + event, files.matches >= occurrence);
                    files.assertPreserved();
                    cases++;
                }
            }
        }
        System.out.println("Disk fault/cancellation cases: " + cases);
    }

    @Test public void emptyAndChunkBoundaryFilesHaveExactHashes() throws Exception {
        for (int size : new int[] {0, 1, 262143, 262144, 262145, 1048593}) {
            DiskFiles files = new DiskFiles(newRoot("size-" + size), size);
            Transfer.Outcome outcome = Transfer.run(files, true, size,
                    Files.getLastModifiedTime(files.source).toMillis());
            assertEquals("MOVED", outcome.status());
            assertEquals(hex(files.original), outcome.hash());
            assertFalse(Files.exists(files.source));
            files.assertPreserved();
        }
    }

    @Test public void abruptProcessDeathNeverRemovesTheOnlyCompleteCopy() throws Exception {
        String separator = System.getProperty("path.separator");
        String classpath = Path.of(TransferSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + separator + Path.of(Transfer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String event : List.of("target-write-after", "target-sync-after", "rename-after",
                "journal-VERIFIED_COPY-after", "journal-DELETE_PENDING-after",
                "source-delete-before", "source-delete-after")) {
            Path root = newRoot("crash");
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", classpath, CrashWorker.class.getName(), root.toString(), event)
                    .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                fail("Crash worker timed out: " + event);
            }
            assertEquals("Worker did not halt at " + event + ": "
                    + new String(Files.readAllBytes(root.resolve("child.log")), StandardCharsets.UTF_8),
                    73, process.exitValue());
            byte[] original = new byte[600_013];
            new Random(2419).nextBytes(original);
            Path source = root.resolve("DCIM/Camera/IMG_0001.jpg");
            boolean intact = Files.exists(source) && Arrays.equals(original, Files.readAllBytes(source));
            try (var paths = Files.walk(root.resolve("Pictures"))) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    intact |= Arrays.equals(original, Files.readAllBytes(file));
                }
            }
            assertTrue("Process death lost data: " + event, intact);
            assertArrayEquals("Existing destination must survive process death",
                    "existing file must survive".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(root.resolve("Pictures/\u8bbe\u5907\u5f52\u6863/V2419A/IMG_0001.jpg")));
        }
    }

    @Test public void damagedOrMissingFinalDestinationNeverDeletesSource() throws Exception {
        for (String status : List.of("VERIFIED_COPY", "DELETE_PENDING")) {
            for (String damage : List.of("missing", "truncated", "same-size-corruption")) {
                DiskFiles files = new DiskFiles(newRoot("damage"), 600_013) {
                    @Override public void record(String current, String hash) throws IOException {
                        super.record(current, hash);
                        if (!current.equals(status)) return;
                        if (damage.equals("missing")) Files.delete(target);
                        else if (damage.equals("truncated")) Files.write(target, new byte[0]);
                        else {
                            byte[] bytes = Files.readAllBytes(target);
                            bytes[bytes.length / 2] ^= 1;
                            Files.write(target, bytes);
                        }
                    }
                };
                long modified = Files.getLastModifiedTime(files.source).toMillis();
                assertThrows(IOException.class,
                        () -> Transfer.run(files, true, files.original.length, modified));
                assertArrayEquals(files.original, Files.readAllBytes(files.source));
                files.assertPreserved();
            }
        }
    }

    public static final class CrashWorker {
        public static void main(String[] args) throws Exception {
            DiskFiles files = new DiskFiles(Path.of(args[0]), 600_013);
            files.stopAt = args[1];
            files.fault = Fault.HALT;
            Transfer.run(files, true, files.original.length, Files.getLastModifiedTime(files.source).toMillis());
            throw new AssertionError("Requested crash boundary not reached");
        }
    }
}
