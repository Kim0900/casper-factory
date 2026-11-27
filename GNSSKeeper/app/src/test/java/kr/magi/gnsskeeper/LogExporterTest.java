package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

public final class LogExporterTest {
    private final AtomicBoolean cancel = new AtomicBoolean();
    private static File temp() throws IOException { return Files.createTempDirectory("gnss-export-synthetic-").toFile(); }
    private static File session(File root, String id, String state) throws IOException {
        File dir = new File(root, id); assertTrue(dir.mkdirs());
        Files.write(new File(dir, "observer_passive.csv").toPath(), "provider,latitude,longitude\nnetwork,1.25,2.5\n".getBytes(StandardCharsets.UTF_8));
        if (state != null) Files.write(new File(dir, "session.json").toPath(), ("{\"state\":\"" + state + "\"}\n").getBytes(StandardCharsets.UTF_8));
        return dir;
    }
    private LogExporter.Archive archive() throws Exception {
        File root = temp(); session(root, "PASSIVE_20261103_070000_abc", "CLOSED");
        return LogExporter.prepare(LogExporter.listSessions(root, null), temp(), "0.2.8", cancel);
    }
    @Test public void bothStorageRootsAndLegacySessionsAreDiscoverable() throws Exception {
        File external = temp(), internal = temp();
        session(external, "PASSIVE_20261103_070000_abc", "CLOSED");
        session(internal, "ACTIVE_20261103_080000_def", "INCOMPLETE");
        File legacy = new File(external, "ACTIVE_20261031_090000"); assertTrue(legacy.mkdir());
        Files.write(new File(legacy, legacy.getName() + ".csv").toPath(), "legacy\n".getBytes(StandardCharsets.UTF_8));
        List<LogExporter.Session> found = LogExporter.listSessions(external, internal);
        assertEquals(3, found.size()); assertEquals("internal", found.get(0).storage);
        assertEquals("ACTIVE_20261103_080000_def", found.get(0).id);
    }
    @Test public void absentRootsAndEmptyOrUnknownDirectoriesAreNotFalseSessions() throws Exception {
        File root = temp(); assertTrue(new File(root, "not_a_session").mkdir());
        assertTrue(new File(root, "PASSIVE_20261103_empty").mkdir());
        assertTrue(LogExporter.listSessions(root, new File(root, "missing")).isEmpty());
    }
    @Test public void zipPreservesSourceBytesAndHashesAndIncompleteState() throws Exception {
        File root = temp();
        File one = session(root, "PASSIVE_20261103_070000_abc", "CLOSED");
        File two = session(root, "ACTIVE_20261103_080000_def", "OPEN");
        File original = new File(one, "observer_passive.csv"); long modified = original.lastModified();
        byte[] expected = Files.readAllBytes(original.toPath());
        LogExporter.Archive a = LogExporter.prepare(LogExporter.listSessions(root, null), temp(), "0.2.8", cancel);
        try (ZipFile zip = new ZipFile(a.file)) {
            assertEquals(5, zip.size());
            byte[] exported = read(zip.getInputStream(zip.getEntry("external/" + one.getName() + "/observer_passive.csv")));
            assertArrayEquals(expected, exported);
            String manifest = new String(read(zip.getInputStream(zip.getEntry("export_manifest.json"))), StandardCharsets.UTF_8);
            assertTrue(manifest.contains(LogExporter.digest(original, cancel)));
            assertTrue(manifest.contains("\"original_metadata_state\":\"OPEN\""));
            assertTrue(manifest.contains("\"encrypted\":false"));
        }
        assertArrayEquals(expected, Files.readAllBytes(original.toPath())); assertEquals(modified, original.lastModified());
        assertTrue(new File(two, "session.json").exists());
    }
    @Test public void onlySelectedSessionsAndKnownLogFilesAreExported() throws Exception {
        File root = temp(); File selected = session(root, "PASSIVE_20261103_070000_abc", null);
        session(root, "ACTIVE_20261103_080000_def", "CLOSED");
        Files.write(new File(selected, "private_secret.txt").toPath(), "must-not-export".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "private_secret.txt").toPath(), "must-not-export".getBytes(StandardCharsets.UTF_8));
        List<LogExporter.Session> found = LogExporter.listSessions(root, null);
        LogExporter.Archive a = LogExporter.prepare(Arrays.asList(found.get(1)), temp(), "0.2.8", cancel);
        try (ZipFile zip = new ZipFile(a.file)) {
            assertEquals(2, zip.size());
            assertTrue(new String(read(zip.getInputStream(zip.getEntry("export_manifest.json"))), StandardCharsets.UTF_8).contains("\"MISSING\""));
            assertNull(zip.getEntry("private_secret.txt"));
        }
    }
    @Test public void linkedSessionOutsideRootIsNeverDiscovered() throws Exception {
        File root = temp(), outside = temp(); File dir = session(outside, "ACTIVE_20261103_080000_def", "CLOSED");
        Files.createSymbolicLink(new File(root, dir.getName()).toPath(), dir.toPath());
        assertTrue(LogExporter.listSessions(root, null).isEmpty());
    }
    @Test public void linkedLogFileIsRejected() throws Exception {
        File root = temp(), dir = session(root, "ACTIVE_20261103_080000_def", "CLOSED");
        File source = new File(dir, "observer_passive.csv"); Files.delete(source.toPath());
        File outside = File.createTempFile("outside", ".csv");
        Files.createSymbolicLink(source.toPath(), outside.toPath());
        try { LogExporter.listSessions(root, null); fail("Followed linked log file"); } catch (IOException expected) { }
    }
    @Test public void linkedRootSubstitutionAfterSelectionIsRejected() throws Exception {
        File parent = temp(), root = new File(parent, "logs");
        session(root, "ACTIVE_20261103_080000_def", "CLOSED");
        List<LogExporter.Session> selected = LogExporter.listSessions(root, null);
        File moved = new File(parent, "moved"); assertTrue(root.renameTo(moved));
        Files.createSymbolicLink(root.toPath(), moved.toPath()); File cache = temp();
        try { LogExporter.prepare(selected, cache, "0.2.8", cancel); fail("Followed substituted root"); } catch (IOException expected) { }
        assertEquals(0, cache.listFiles().length);
    }
    @Test public void cancellationDuringPreparationLeavesNoZipAndKeepsSources() throws Exception {
        File root = temp(), dir = session(root, "PASSIVE_20261103_070000_abc", "CLOSED"), cache = temp();
        cancel.set(true);
        try { LogExporter.prepare(LogExporter.listSessions(root, null), cache, "0.2.8", cancel); fail("Cancelled preparation succeeded"); }
        catch (LogExporter.Cancelled expected) { }
        assertEquals(0, cache.listFiles().length); assertTrue(new File(dir, "observer_passive.csv").exists());
    }
    @Test public void removedSourceFailsAndCleansPartialZip() throws Exception {
        File root = temp(), dir = session(root, "PASSIVE_20261103_070000_abc", null), cache = temp();
        List<LogExporter.Session> selected = LogExporter.listSessions(root, null);
        Files.delete(new File(dir, "observer_passive.csv").toPath());
        try { LogExporter.prepare(selected, cache, "0.2.8", cancel); fail("Missing source accepted"); } catch (IOException expected) { }
        assertEquals(0, cache.listFiles().length);
    }
    private static class MemoryDestination implements LogExporter.Destination {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean opened;
        @Override public OutputStream openWrite() throws IOException { opened = true; return bytes; }
        @Override public InputStream openRead() throws IOException { return new ByteArrayInputStream(bytes.toByteArray()); }
    }
    @Test public void destinationRoundTripIsByteIdentical() throws Exception {
        LogExporter.Archive a = archive(); MemoryDestination dest = new MemoryDestination();
        LogExporter.save(a, dest, cancel);
        assertArrayEquals(Files.readAllBytes(a.file.toPath()), dest.bytes.toByteArray());
    }
    @Test public void destinationCorruptionNeverReportsSuccess() throws Exception {
        LogExporter.Archive a = archive();
        MemoryDestination dest = new MemoryDestination() { @Override public InputStream openRead() { return new ByteArrayInputStream(new byte[]{1,2,3}); } };
        try { LogExporter.save(a, dest, cancel); fail("Corrupt saved ZIP accepted"); } catch (IOException expected) { }
    }
    @Test public void outputWriteFailurePropagates() throws Exception {
        LogExporter.Archive a = archive();
        MemoryDestination dest = new MemoryDestination() { @Override public OutputStream openWrite() { return new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("Synthetic no space"); } }; } };
        try { LogExporter.save(a, dest, cancel); fail("Write failure hidden"); } catch (IOException expected) { }
        assertTrue(a.file.exists());
    }
    @Test public void providerCloseCommitFailurePropagates() throws Exception {
        LogExporter.Archive a = archive();
        MemoryDestination dest = new MemoryDestination() { @Override public OutputStream openWrite() { return new ByteArrayOutputStream() { @Override public void close() throws IOException { throw new IOException("Synthetic commit failed"); } }; } };
        try { LogExporter.save(a, dest, cancel); fail("Provider commit failure hidden"); } catch (IOException expected) { }
    }
    @Test public void cancelledSaveNeverOpensDestination() throws Exception {
        LogExporter.Archive a = archive(); MemoryDestination dest = new MemoryDestination(); cancel.set(true);
        try { LogExporter.save(a, dest, cancel); fail("Cancelled save succeeded"); } catch (LogExporter.Cancelled expected) { }
        assertFalse(dest.opened);
    }
    @Test public void tamperedPreparedArchiveRejectedBeforeOpeningDestination() throws Exception {
        LogExporter.Archive a = archive(); Files.write(a.file.toPath(), new byte[]{1,2,3}); MemoryDestination dest = new MemoryDestination();
        try { LogExporter.save(a, dest, cancel); fail("Tampered archive accepted"); } catch (IOException expected) { }
        assertFalse(dest.opened);
    }
    @Test public void cancellationDuringCopyIsNotSuccess() throws Exception {
        LogExporter.Archive a = archive();
        MemoryDestination dest = new MemoryDestination() {
            @Override public OutputStream openWrite() { return new ByteArrayOutputStream() { @Override public synchronized void write(byte[] b, int off, int len) { super.write(b,off,len); cancel.set(true); } }; }
        };
        try { LogExporter.save(a, dest, cancel); fail("In-flight cancellation accepted"); } catch (LogExporter.Cancelled expected) { }
    }
    @Test public void duplicateSelectionFailsWithoutPartialZip() throws Exception {
        File root = temp(), cache = temp(); session(root, "ACTIVE_20261103_080000_def", "CLOSED");
        LogExporter.Session s = LogExporter.listSessions(root, null).get(0);
        try { LogExporter.prepare(Arrays.asList(s,s), cache, "0.2.8", cancel); fail("Duplicate selection accepted"); } catch (IOException expected) { }
        assertEquals(0, cache.listFiles().length);
    }
    private static byte[] read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n; while ((n=in.read(buffer))!=-1) out.write(buffer,0,n); return out.toByteArray();
        }
    }
}
