package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** v0.3 (Q-1128-G1I): pure-JVM tests for §18 auto recovery, §18-0 log cap and §18-7 request invariance. */
public final class RecoveryV03Test {
    /** In-memory store; {@code failCommits} simulates SharedPreferences.commit() returning false. */
    static final class MemStore implements RecoveryState.Store {
        RecoveryState saved = new RecoveryState();
        boolean failCommits;
        int commits;
        @Override public RecoveryState load() { return saved.copy(); }
        @Override public boolean commit(RecoveryState s) { commits++; if (failCommits) return false; saved = s.copy(); return true; }
    }
    private static RecoveryState running(String mode) {
        RecoveryState s = new RecoveryState(); s.runIntent = true; s.lastMode = mode; s.sessionClosed = false; return s;
    }

    // ---- §18-6 restore order / loop limit ----
    @Test public void nullIntentWithRunIntentFalseNeverStarts() {
        MemStore store = new MemStore(); RecoveryState s = new RecoveryState(); // default run_intent=false
        assertEquals(RecoveryPolicy.Outcome.SKIP_NOT_RUNNING,
                RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 7, 1_000));
        assertTrue(s.history.isEmpty()); assertEquals(0, store.commits);
    }
    @Test public void userStopInAppDisablesRestart() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE");
        assertTrue(RecoveryPolicy.onUserStop(store, s));
        assertFalse(store.saved.runIntent);
        assertEquals(RecoveryPolicy.Outcome.SKIP_NOT_RUNNING,
                RecoveryPolicy.onSystemRestart(store, store.load(), ExitReasons.Kind.OS_KILL, 1, 5));
    }
    @Test public void taskManagerOrForceStopClearsRunIntentAndIsNotRestored() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE");
        assertEquals(RecoveryPolicy.Outcome.SKIP_USER_STOP,
                RecoveryPolicy.onSystemRestart(store, s, ExitReasons.classify(ExitReasons.USER_REQUESTED), 1, 5));
        assertFalse(store.saved.runIntent); assertTrue(s.history.isEmpty());
    }
    @Test public void haltedIsCheckedBeforeEverythingAndOnlyManualStartClearsIt() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE"); s.halted = true;
        assertEquals(RecoveryPolicy.Outcome.SKIP_HALTED, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.USER_STOP, 1, 5));
        assertTrue(s.runIntent); // halted check came first: nothing else evaluated
        // time passing / reboot does not clear the halt
        assertEquals(RecoveryPolicy.Outcome.SKIP_HALTED, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 2, 99_000_000));
        assertTrue(RecoveryPolicy.onManualStart(store, s, "PASSIVE"));
        assertFalse(store.saved.halted); assertTrue(store.saved.history.isEmpty()); assertEquals("PASSIVE", store.saved.lastMode);
    }
    @Test public void threeRestartsInTenMinutesSameBootHaltsTheFourth() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE");
        for (int i = 0; i < 3; i++) {
            assertEquals(RecoveryPolicy.Outcome.RESTORE, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 4, 1_000 + i * 60_000L));
            RecoveryPolicy.onRestoreSucceeded(store, s); // successes do not reset the window
        }
        assertEquals(RecoveryPolicy.Outcome.HALT_LOOP, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 4, 300_000));
        assertTrue(store.saved.halted);
    }
    @Test public void windowUsesSameBootMonotonicTimeOnly() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE");
        s.history.add(new long[]{3, 1_000}); s.history.add(new long[]{3, 2_000}); s.history.add(new long[]{3, 3_000});
        // different boot: previous-boot attempts are outside the window
        assertEquals(RecoveryPolicy.Outcome.RESTORE, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 4, 4_000));
        assertEquals(1, s.history.size());
        // same boot but > 10 min later: expired
        RecoveryState t = running("ACTIVE"); t.history.add(new long[]{4, 0}); t.history.add(new long[]{4, 1}); t.history.add(new long[]{4, 2});
        assertEquals(RecoveryPolicy.Outcome.RESTORE, RecoveryPolicy.onSystemRestart(store, t, ExitReasons.Kind.OS_KILL, 4, RecoveryPolicy.LOOP_WINDOW_MS + 3));
    }
    @Test public void attemptIsPersistedBeforeWorkSoCrashBeforeSessionCountsAsFailure() {
        MemStore store = new MemStore(); RecoveryState s = running("ACTIVE");
        assertEquals(RecoveryPolicy.Outcome.RESTORE, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.APP_CRASH, 1, 10));
        assertEquals(1, store.saved.consecutiveFailures); assertEquals(1, store.saved.history.size());
        // process dies before session start → next re-creation reloads persisted state
        RecoveryState again = store.load();
        assertEquals(RecoveryPolicy.Outcome.RESTORE, RecoveryPolicy.onSystemRestart(store, again, ExitReasons.Kind.APP_CRASH, 1, 20));
        assertEquals(2, store.saved.consecutiveFailures);
        assertEquals(RecoveryPolicy.Outcome.HALT_LOOP, RecoveryPolicy.onSystemRestart(store, store.load(), ExitReasons.Kind.APP_CRASH, 1, 30));
    }
    @Test public void commitFailureFailsClosed() {
        MemStore store = new MemStore(); store.failCommits = true; RecoveryState s = running("ACTIVE");
        assertEquals(RecoveryPolicy.Outcome.HALT_COMMIT_FAIL, RecoveryPolicy.onSystemRestart(store, s, ExitReasons.Kind.OS_KILL, 1, 10));
    }

    // ---- §18-1/§18-4 exit classification, matching, de-duplication ----
    @Test public void exitKindsAreDistinguished() {
        assertEquals(ExitReasons.Kind.USER_STOP, ExitReasons.classify(ExitReasons.USER_REQUESTED));
        assertEquals(ExitReasons.Kind.OS_KILL, ExitReasons.classify(ExitReasons.LOW_MEMORY));
        assertEquals(ExitReasons.Kind.OS_KILL, ExitReasons.classify(ExitReasons.SIGNALED));
        assertEquals(ExitReasons.Kind.APP_CRASH, ExitReasons.classify(ExitReasons.CRASH));
        assertEquals(ExitReasons.Kind.APP_CRASH, ExitReasons.classify(ExitReasons.ANR));
        assertEquals(ExitReasons.Kind.UNKNOWN, ExitReasons.classify(ExitReasons.UNKNOWN));
        assertEquals("REASON_USER_REQUESTED", ExitReasons.name(ExitReasons.USER_REQUESTED));
    }
    @Test public void exitMatchesPreviousPidAndTimeAndIsCountedOnce() {
        List<ExitReasons.Record> r = Arrays.asList(
                new ExitReasons.Record(111, 5_000, ExitReasons.LOW_MEMORY, "lmk"),
                new ExitReasons.Record(222, 6_000, ExitReasons.CRASH, "other pid"),
                new ExitReasons.Record(111, 500, ExitReasons.CRASH, "before process start"));
        List<String> seen = new ArrayList<>();
        ExitReasons.Record m = ExitReasons.match(r, 111, 1_000, 10_000, seen);
        assertNotNull(m); assertEquals(5_000, m.timestampMs);
        seen.add(m.key());
        assertNull(ExitReasons.match(r, 111, 1_000, 10_000, seen));
        assertNull(ExitReasons.match(r, 0, 0, 10_000, null));
    }
    @Test public void seenListIsBounded() {
        RecoveryState s = new RecoveryState();
        for (int i = 0; i < 50; i++) { s.markSeen("k" + i); s.markSeen("k" + i); }
        assertEquals(RecoveryState.MAX_SEEN, s.exitSeen.size()); assertEquals("k49", s.exitSeen.get(s.exitSeen.size() - 1));
    }
    @Test public void stateEncodingRoundTrips() {
        List<long[]> h = new ArrayList<>(); h.add(new long[]{3, 10}); h.add(new long[]{4, 20});
        List<long[]> out = new ArrayList<>(); RecoveryState.decodeHistory(RecoveryState.encodeHistory(h), out);
        assertEquals(2, out.size()); assertEquals(4, out.get(1)[0]); assertEquals(20, out.get(1)[1]);
        RecoveryState.decodeHistory("garbage;5:6", out); assertEquals(1, out.size());
    }

    // ---- §18-4 gap computation / event format ----
    @Test public void sameBootUsesMonotonicAndTrimsHeartbeatLagWithExitTime() {
        RecoveryGap g = RecoveryGap.compute(5, 100_000, 50_000, 104_000, 5, 160_000, 110_000);
        assertTrue(g.exact); assertTrue(g.monotonic); assertFalse(g.reboot);
        assertEquals(56_000, g.gapMs); assertEquals(104_000, g.offWallMs);
    }
    @Test public void clockCorrectionIsNotReboot() {
        // wall jumped +1 h while monotonic advanced 60 s
        RecoveryGap g = RecoveryGap.compute(5, 100_000, 50_000, 0, 5, 100_000 + 3_660_000, 110_000);
        assertFalse(g.reboot); assertTrue(g.clockAdjusted); assertTrue(g.monotonic);
        assertFalse(g.exact); assertEquals(60_000, g.gapMs);
    }
    @Test public void rebootIsWallClockAndEstimated() {
        RecoveryGap g = RecoveryGap.compute(5, 100_000, 50_000, 0, 6, 400_000, 2_000);
        assertTrue(g.reboot); assertFalse(g.monotonic); assertFalse(g.exact); assertEquals(300_000, g.gapMs);
    }
    @Test public void recoveryGapDetailFormat() {
        String d = RecoveryGap.compute(5, 100_000, 50_000, 0, 5, 112_000, 62_000).detail("AUTO", false);
        assertEquals("off=100000;on=112000;gap_s=12.0;bound=ESTIMATED;off_source=HEARTBEAT;clock=MONO;reboot=0;clock_adjusted=0;censored=0;cause=AUTO", d);
        assertFalse(d.contains(","));
    }

    // ---- §18-0 log cap ----
    @Test public void budgetStopsSummaryAtCapMinusReserveAndNeverExceedsHardCap() {
        LogBudget b = new LogBudget(1_000, 100);
        assertTrue(b.trySummary(850)); assertTrue(b.trySummary(50)); assertFalse(b.trySummary(1));
        assertTrue(b.capped()); assertFalse(b.trySummary(1));
        assertTrue(b.tryTerminal(90)); assertFalse(b.tryTerminal(20)); assertTrue(b.total() <= 1_000);
        assertEquals(20L * 1024 * 1024, LogBudget.HARD_CAP_BYTES); assertEquals(64L * 1024, LogBudget.TERMINAL_RESERVE_BYTES);
    }
    @Test public void cappedLoggerStopsSummaryWritesRecordsLogCapAndStillCloses() throws Exception {
        File root = Files.createTempDirectory("keeper-cap-").toFile();
        LogBudget budget = new LogBudget(4_096, 1_024);
        CsvLogger l = new CsvLogger(root, "ACTIVE", 1, 2_000_000_000L, Map.of("experiment_id", "cap-test"), CsvLogger.FILES, budget);
        for (int i = 0; i < 200; i++)
            l.logObserver(new LocationSample(1, 3_000_000_000L + i, 3_100_000_000L + i, i, 1, 2, 3, 4, 5, 6, "gps", false));
        assertTrue(l.capped());
        l.event(5, 6, "RECOVERY_GAP", "gap_s=1.0");
        l.finish(4_000_000_000L, "TEST_END");
        long total = 0;
        for (File f : l.sessionDir.listFiles()) total += f.length();
        assertTrue("session folder " + total + " > hard cap", total <= 4_096);
        List<String> events = Files.readAllLines(new File(l.sessionDir, "events.csv").toPath());
        assertEquals(1, events.stream().filter(e -> e.contains(",LOG_CAP,")).count());
        assertTrue(events.stream().anyMatch(e -> e.contains(",RECOVERY_GAP,")));
        assertTrue(events.get(events.size() - 1).contains(",END,TEST_END"));
        String meta = new String(Files.readAllBytes(new File(l.sessionDir, "session.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"CLOSED\"")); assertTrue(meta.contains("\"schema_version\": \"3\""));
    }
    @Test public void schemaThreeAppendsColumnsOnlyAtTheEnd() throws Exception {
        CsvLogger l = new CsvLogger(Files.createTempDirectory("keeper-schema-").toFile(), "ACTIVE", 1, 2, Map.of());
        l.logMeasurement(1, 2, 1, 0, 10, 6, 4, 30f, 8, 1, 1000);
        l.logConstellation(1, 2, ConstellationSummary.rows(new int[]{1, 6}, new float[]{1575.42e6f, 1176.45e6f},
                new boolean[]{true, false}, new float[]{40f, 30f}, new float[]{Float.NaN, 33f}));
        l.finish(3, "END");
        List<String> m = Files.readAllLines(new File(l.sessionDir, "gnss_measurements.csv").toPath());
        assertTrue(m.get(0).startsWith("wall_time_ms,received_elapsed_nanos,event_index,clock_discontinuity,measurement_count,l1_like_entries,l5_like_entries,avg_cn0_dbhz,"));
        assertTrue(m.get(0).endsWith(",adr_valid_count,multipath_detected_count,clock_interval_ms"));
        assertEquals(11, m.get(1).split(",").length);
        List<String> c = Files.readAllLines(new File(l.sessionDir, "gnss_constellation.csv").toPath());
        assertEquals(ConstellationSummary.HEADER, c.get(0));
        assertEquals("1,2,GALILEO,L5,1,0,30.0,30.0,33.0", c.get(1));
        assertEquals("1,2,GPS,L1,1,1,40.0,40.0,NaN", c.get(2));
    }
    @Test public void constellationSummaryIsAtMostEveryFiveSeconds() {
        ConstellationSummary s = new ConstellationSummary();
        assertTrue(s.due(0)); assertFalse(s.due(4_999_999_999L)); assertTrue(s.due(5_000_000_000L));
        assertEquals("NA", ConstellationSummary.band(Float.NaN)); assertEquals("OTHER", ConstellationSummary.band(1381.05e6f)); // existing L5-like range 1150–1250 MHz also contains GPS L2 (known, report §1-7)
    }

    // ---- §18-7 request invariance ----
    @Test public void restartPathAndManualPathProduceIdenticalRequests() {
        for (String mode : new String[]{GnssSnapshot.MODE_ACTIVE, GnssSnapshot.MODE_PASSIVE}) {
            List<RequestSpec> manual = RequestSpec.forStart(mode, GnssKeeperServiceCauses.MANUAL);
            assertEquals(manual, RequestSpec.forStart(mode, GnssKeeperServiceCauses.AUTO));
            assertEquals(manual, RequestSpec.forStart(mode, GnssKeeperServiceCauses.NOTIFICATION));
        }
    }
    @Test public void requestParametersAreUnchangedFromV028() {
        RequestSpec p = RequestSpec.PASSIVE_OBSERVER, g = RequestSpec.ACTIVE_GPS;
        assertEquals(1000L, p.intervalMs); assertEquals(0L, p.minIntervalMs); assertEquals(0f, p.minDistanceM, 0f); assertFalse(p.highAccuracy);
        assertEquals(1000L, g.intervalMs); assertEquals(500L, g.minIntervalMs); assertEquals(0f, g.minDistanceM, 0f); assertTrue(g.highAccuracy);
        assertTrue(RequestSpec.GNSS_MEASUREMENTS.fullTracking);
        assertEquals(Arrays.asList(p), RequestSpec.forStart(GnssSnapshot.MODE_PASSIVE, "MANUAL"));
        assertEquals(Arrays.asList(p, g, RequestSpec.GNSS_STATUS, RequestSpec.GNSS_MEASUREMENTS),
                RequestSpec.forStart(GnssSnapshot.MODE_ACTIVE, "MANUAL"));
    }

    /** Cause strings duplicated here so this pure test does not load the Android Service class. */
    static final class GnssKeeperServiceCauses { static final String MANUAL = "MANUAL", AUTO = "AUTO_RESTART", NOTIFICATION = "NOTIFICATION"; }
}
