package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** New repair tests. Does not replace or reinterpret historical S19/SR05 outputs. */
public final class RepairRegressionTest {
    private static File root() throws IOException { return Files.createTempDirectory("keeper-synthetic-").toFile(); }
    private static CsvLogger logger(File root, String mode, long epoch) throws IOException {
        return new CsvLogger(root, mode, epoch, 2_000_000_000L, Map.of("experiment_id", "synthetic-test"));
    }
    private static LocationSample sample(String provider, long fix, long receive, long seq) {
        return new LocationSample(1234, fix, receive, seq, 1.25, 2.5, 3.0, 4, 5, 6, provider, true);
    }
    @Test public void rapidSessionsAreExclusiveWithOneHeader() throws Exception {
        File base = root(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            CsvLogger l = logger(base, "PASSIVE", i);
            assertTrue(ids.add(l.sessionId));
            l.logObserver(sample("network", 3_000_000_000L, 3_100_000_000L, 1));
            l.finish(4_000_000_000L, "TEST_END");
            List<String> lines = Files.readAllLines(new File(l.sessionDir, "observer_passive.csv").toPath());
            assertEquals(2, lines.size());
            String meta = new String(Files.readAllBytes(new File(l.sessionDir, "session.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(meta.contains("CLOSED")); assertTrue(meta.contains(l.sessionId));
        }
        assertEquals(100, base.listFiles().length);
    }
    @Test public void primarySchemaIsIdenticalAndDirectGpsCannotPolluteIt() throws Exception {
        File root = root(); String commonHeader = null;
        for (String mode : new String[]{"PASSIVE", "ACTIVE"}) {
            CsvLogger l = logger(root, mode, 1);
            for (String provider : new String[]{"gps", "network", "fused"})
                l.logObserver(sample(provider, 3_000_000_000L, 3_100_000_000L, 1));
            l.logDirect(sample("gps", 3_000_000_000L, 3_100_000_000L, 2));
            l.finish(4_000_000_000L, "TEST_END");
            List<String> lines = Files.readAllLines(new File(l.sessionDir, "observer_passive.csv").toPath());
            if (commonHeader == null) commonHeader = lines.get(0); else assertEquals(commonHeader, lines.get(0));
            assertEquals(4, lines.size());
            for (int i = 1; i <= 3; i++) {
                String[] row = lines.get(i).split(","); assertEquals(21, row.length);
                assertEquals("passive", row[8]); assertEquals(new String[]{"gps", "network", "fused"}[i-1], row[9]);
                assertEquals(mode, row[7]); assertEquals("synthetic-test", row[4]);
            }
            File direct = new File(l.sessionDir, "diagnostic_gps.csv");
            assertEquals("ACTIVE".equals(mode), direct.exists());
            if (direct.exists()) assertEquals(2, Files.readAllLines(direct.toPath()).size());
        }
    }
    @Test public void lateDuplicateMockAndMissingIntervalsAreExplicit() throws Exception {
        CsvLogger l = logger(root(), "PASSIVE", 7);
        l.logObserver(sample("network", 1_000_000_000L, 3_000_000_000L, 1));
        l.logObserver(sample("network", 1_000_000_000L, 9_000_000_000L, 2));
        l.finish(10_000_000_000L, "TEST_END");
        List<String> lines = Files.readAllLines(new File(l.sessionDir, "observer_passive.csv").toPath());
        String[] first = lines.get(1).split(","), second = lines.get(2).split(",");
        assertEquals("false", first[18]); assertEquals("true", second[19]); assertEquals("true", second[20]);
        assertEquals("6000", second[17]); assertEquals("8000", second[16]);
    }
    private static final class TrackingWriter extends StringWriter {
        boolean closed;
        final boolean failHeader, failClose;
        TrackingWriter(boolean failHeader, boolean failClose) { this.failHeader = failHeader; this.failClose = failClose; }
        @Override public void flush() { if (failHeader) throw new IllegalStateException("Synthetic header failure"); }
        @Override public void close() throws IOException { closed = true; if (failClose) throw new IOException("Synthetic close failure"); }
    }
    @Test public void partialInitializationClosesEveryOpenedWriter() throws Exception {
        List<TrackingWriter> opened = new ArrayList<>();
        try {
            new CsvLogger(root(), "ACTIVE", 1, 2, Map.of(), file -> {
                if (opened.size() == 3) throw new IOException("Synthetic open failure");
                TrackingWriter w = new TrackingWriter(false, false); opened.add(w); return w;
            });
            fail("Must fail construction");
        } catch (IOException expected) { }
        assertEquals(3, opened.size()); for (TrackingWriter w : opened) assertTrue(w.closed);
    }
    @Test public void headerFailureAlsoClosesFailingWriter() throws Exception {
        List<TrackingWriter> opened = new ArrayList<>();
        try {
            new CsvLogger(root(), "PASSIVE", 1, 2, Map.of(), file -> {
                TrackingWriter w = new TrackingWriter(opened.size() == 1, false); opened.add(w); return w;
            });
            fail("Must fail header");
        } catch (IllegalStateException expected) { }
        for (TrackingWriter w : opened) assertTrue(w.closed);
    }
    @Test public void closeFailureIsReportedAndOtherWritersStillClose() throws Exception {
        List<TrackingWriter> opened = new ArrayList<>();
        CsvLogger l = new CsvLogger(root(), "ACTIVE", 1, 2, Map.of(), file -> {
            TrackingWriter w = new TrackingWriter(false, opened.isEmpty()); opened.add(w); return w;
        });
        try { l.finish(3, "END"); fail("Close failure must surface"); } catch (IOException expected) { }
        for (TrackingWriter w : opened) assertTrue(w.closed);
        assertTrue(new String(Files.readAllBytes(new File(l.sessionDir, "session.json").toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("INCOMPLETE"));
    }
    @Test public void writingClosedSessionFailsInsteadOfReportingSuccess() throws Exception {
        CsvLogger l = logger(root(), "PASSIVE", 1); l.close();
        try { l.logObserver(sample("gps", 3, 4, 1)); fail("Closed writer accepted record"); } catch (IOException expected) { }
    }
    @Test public void staleDataAndFutureTimestampsAreNotFresh() {
        assertFalse(GnssSnapshot.fresh(0, 6_000_000_000L));
        assertFalse(GnssSnapshot.fresh(1, 6_000_000_000L));
        assertFalse(GnssSnapshot.fresh(10, 9));
        assertTrue(GnssSnapshot.fresh(1_000_000_000L, 6_000_000_000L));
        assertFalse(GnssSnapshot.fresh(1_000_000_000L, 6_000_000_001L));
    }
    @Test public void controlSubmissionNeverWaitsFor250ms1sOr6sIo() throws Exception {
        for (int delay : new int[]{250, 1000, 6000}) {
            SessionExecutor s = new SessionExecutor(); long e = s.nextEpoch();
            CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
            assertTrue(s.callback(e, () -> {
                entered.countDown();
                try { Thread.sleep(delay); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime(); long stop = s.nextEpoch();
            assertTrue(s.control(stop, done::countDown));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            assertTrue("Main-side command took " + elapsedMs + "ms", elapsedMs < 200);
            assertTrue(done.await(delay + 2000L, TimeUnit.MILLISECONDS));
            s.retire(() -> { });
        }
    }
    @Test public void staleEpochCallbackCannotEnterNewSession() throws Exception {
        SessionExecutor s = new SessionExecutor(); long old = s.nextEpoch();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        s.callback(old, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        s.callback(old, writes::incrementAndGet);
        long next = s.nextEpoch(); s.control(next, done::countDown); release.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(0, writes.get()); s.retire(() -> { });
    }
    @Test public void boundedBacklogReturnsExplicitFailure() throws Exception {
        SessionExecutor s = new SessionExecutor(2); long e = s.nextEpoch();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        s.callback(e, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(s.callback(e, () -> { }));
        assertFalse(s.callback(e, () -> { }));
        s.retire(done::countDown); release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
    }
    @Test public void retireIsNonBlockingAndCleanupRunsAfterInFlightWrite() throws Exception {
        SessionExecutor s = new SessionExecutor(); long e = s.nextEpoch();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(), cleanedAfter = new AtomicBoolean();
        s.callback(e, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } finished.set(true); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long start = System.nanoTime();
        s.retire(() -> { cleanedAfter.set(finished.get()); done.countDown(); });
        assertTrue((System.nanoTime() - start) / 1_000_000 < 200);
        assertTrue(s.callback(e, () -> fail("Retired callback executed"))); // Stale callback is safely ignored.
        release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(cleanedAfter.get());
    }
}
