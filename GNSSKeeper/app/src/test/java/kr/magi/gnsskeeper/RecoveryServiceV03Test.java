package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.ApplicationExitInfo;
import android.app.Service;
import android.content.Context;
import android.location.GnssStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

/** v0.3 service wiring on simulated API31/36 — not a substitute for the §18-8 device tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 36})
public final class RecoveryServiceV03Test {
    private ServiceController<GnssKeeperService> controller;

    @Before public void before() {
        RuntimeEnvironment.getApplication().getSharedPreferences(RecoveryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.mode = GnssSnapshot.MODE_NONE; GnssSnapshot.running = false;
    }
    @After public void after() {
        if (controller != null) controller.destroy();
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.mode = GnssSnapshot.MODE_NONE; GnssSnapshot.running = false;
    }

    @Test public void stickyRecreationWithRunIntentFalseDoesNotStart() {
        controller = Robolectric.buildService(GnssKeeperService.class).create();
        GnssKeeperService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertFalse(GnssSnapshot.running);
        assertNotEquals("STARTING", GnssSnapshot.phase);
    }

    @Test public void stickyRecreationWhileHaltedDoesNotStartEvenWithRunIntent() {
        RecoveryStore store = new RecoveryStore(RuntimeEnvironment.getApplication());
        RecoveryState s = store.load(); s.runIntent = true; s.lastMode = GnssSnapshot.MODE_ACTIVE; s.halted = true;
        assertTrue(store.commit(s));
        controller = Robolectric.buildService(GnssKeeperService.class).create();
        GnssKeeperService service = controller.get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertTrue(store.load().halted);
    }

    @Test public void recoveryStateSurvivesStoreRoundTrip() {
        RecoveryStore store = new RecoveryStore(RuntimeEnvironment.getApplication());
        RecoveryState s = store.load();
        assertFalse(s.runIntent); assertFalse(s.halted); assertTrue(s.sessionClosed);
        s.runIntent = true; s.pid = 42; s.history.add(new long[]{7, 1234}); s.markSeen("42@99"); s.addPending(5, "RESTART_HALTED", "reason=LOOP");
        assertTrue(store.commit(s));
        RecoveryState r = store.load();
        assertTrue(r.runIntent); assertEquals(42, r.pid); assertEquals(1, r.history.size()); assertEquals(1234, r.history.get(0)[1]);
        assertEquals("42@99", r.exitSeen.get(0)); assertEquals("5|RESTART_HALTED|reason=LOOP", r.pending.get(0));
    }

    @Test public void pureConstantsMatchAndroidFramework() {
        assertEquals(ApplicationExitInfo.REASON_USER_REQUESTED, ExitReasons.USER_REQUESTED);
        assertEquals(ApplicationExitInfo.REASON_USER_STOPPED, ExitReasons.USER_STOPPED);
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, ExitReasons.LOW_MEMORY);
        assertEquals(ApplicationExitInfo.REASON_SIGNALED, ExitReasons.SIGNALED);
        assertEquals(ApplicationExitInfo.REASON_CRASH, ExitReasons.CRASH);
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, ExitReasons.CRASH_NATIVE);
        assertEquals(ApplicationExitInfo.REASON_ANR, ExitReasons.ANR);
        assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ExitReasons.EXCESSIVE_RESOURCE_USAGE);
        assertEquals(ApplicationExitInfo.REASON_OTHER, ExitReasons.OTHER);
        assertEquals(ApplicationExitInfo.REASON_FREEZER, ExitReasons.FREEZER);
        assertEquals(ApplicationExitInfo.REASON_PERMISSION_CHANGE, ExitReasons.PERMISSION_CHANGE);
        assertEquals(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ExitReasons.PACKAGE_UPDATED);
        assertEquals("GPS", ConstellationSummary.constellation(GnssStatus.CONSTELLATION_GPS));
        assertEquals("GLONASS", ConstellationSummary.constellation(GnssStatus.CONSTELLATION_GLONASS));
        assertEquals("QZSS", ConstellationSummary.constellation(GnssStatus.CONSTELLATION_QZSS));
        assertEquals("BEIDOU", ConstellationSummary.constellation(GnssStatus.CONSTELLATION_BEIDOU));
        assertEquals("GALILEO", ConstellationSummary.constellation(GnssStatus.CONSTELLATION_GALILEO));
    }
}
