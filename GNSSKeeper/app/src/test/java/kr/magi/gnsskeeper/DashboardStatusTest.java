package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DashboardStatusTest {
    @Test public void allSevenAndroidThermalLevelsKeepSeverityAndUnknownIsNeverNormal() {
        DashboardStatus.Band[] expected = {DashboardStatus.Band.BLUE, DashboardStatus.Band.GREEN, DashboardStatus.Band.YELLOW,
                DashboardStatus.Band.RED, DashboardStatus.Band.RED, DashboardStatus.Band.RED, DashboardStatus.Band.RED};
        for (int i = 0; i < expected.length; i++) assertEquals("Android level " + i, expected[i], DashboardStatus.thermal(i).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.thermal(-1).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.thermal(7).band);
        assertNotEquals(DashboardStatus.thermal(3).label, DashboardStatus.thermal(6).label);
    }
    @Test public void passiveAbsenceAndLongGapNeverBecomeActiveFailure() {
        long now = 100_000_000_000L;
        assertEquals(DashboardStatus.Band.BLUE, DashboardStatus.location(true, true, 0, now).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.location(true, true, 1, now).band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.location(true, false, 1, now).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.location(false, false, 1, now).band);
    }
    @Test public void locationBoundariesUseMeasurementTimeAndRejectFutureClock() {
        long now = 100_000_000_000L;
        assertEquals(DashboardStatus.Band.GREEN, DashboardStatus.location(true, false, now - 5_000_000_000L, now).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.location(true, false, now - 5_000_000_001L, now).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.location(true, false, now - 15_000_000_000L, now).band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.location(true, false, now - 15_000_000_001L, now).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.location(true, false, now + 1, now).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.location(true, false, -1, now).band);
    }
    @Test public void validatedCapabilityAloneDoesNotClaimInternet() {
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.network(false, true, true, true).band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.network(true, false, true, true).band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.network(true, true, false, true).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.network(true, true, true, false).band);
        assertEquals(DashboardStatus.Band.GREEN, DashboardStatus.network(true, true, true, true).band);
    }
    @Test public void precisePermissionAndEnabledLocationBothRequiredForGreen() {
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.permission(true, true, false).band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.permission(false, false, true).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.permission(false, true, true).band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.permission(true, true, null).band);
        assertEquals(DashboardStatus.Band.GREEN, DashboardStatus.permission(true, true, true).band);
    }
    @Test public void serviceAndPowerDoNotImplyLocationReception() {
        assertEquals(DashboardStatus.Band.GREEN, DashboardStatus.service("RUNNING").band);
        assertEquals(DashboardStatus.Band.BLUE, DashboardStatus.service("STARTING").band);
        assertEquals(DashboardStatus.Band.BLUE, DashboardStatus.service("STOPPING").band);
        assertEquals(DashboardStatus.Band.RED, DashboardStatus.service("ERROR").band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.service("STOPPED").band);
        assertEquals(DashboardStatus.Band.UNKNOWN, DashboardStatus.power(null).band);
        assertEquals(DashboardStatus.Band.YELLOW, DashboardStatus.power(true).band);
        assertEquals(DashboardStatus.Band.GREEN, DashboardStatus.power(false).band);
    }
}
