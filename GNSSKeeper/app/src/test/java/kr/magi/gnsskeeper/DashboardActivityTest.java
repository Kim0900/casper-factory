package kr.magi.gnsskeeper;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Context;
import android.os.PowerManager;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Real Activity wiring on simulated API31/36, not hardware thermal or connectivity testing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 36})
@LooperMode(LooperMode.Mode.PAUSED)
public final class DashboardActivityTest {
    private ActivityController<MainActivity> controller;
    @Before public void before() {
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.mode = GnssSnapshot.MODE_NONE;
        GnssSnapshot.running = false; GnssSnapshot.resetMeasurements();
    }
    @After public void after() {
        if (controller != null) controller.pause().stop().destroy();
        GnssSnapshot.phase = "STOPPED"; GnssSnapshot.mode = GnssSnapshot.MODE_NONE;
        GnssSnapshot.running = false; GnssSnapshot.resetMeasurements();
    }
    private MainActivity start() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }
    @Test public void thermalOsStatusDrivesColorWhileBatteryTemperatureIsSeparate() {
        PowerManager pm = (PowerManager)RuntimeEnvironment.getApplication().getSystemService(Context.POWER_SERVICE);
        shadowOf(pm).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE);
        MainActivity activity = start(); DeviceDashboard.render(activity, 100_000_000_000L);
        TextView card = activity.findViewById(R.id.cardThermal);
        assertEquals(DashboardStatus.Band.RED.color, card.getCurrentTextColor());
        assertTrue(card.getText().toString().contains("심한 열 제한"));
        assertTrue(card.getText().toString().contains("배터리"));
        assertTrue(card.getContentDescription().toString().contains("빨강"));
        shadowOf(pm).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_NONE);
        DeviceDashboard.render(activity, 100_000_000_000L);
        assertEquals(DashboardStatus.Band.BLUE.color, card.getCurrentTextColor());
        assertTrue(card.getText().toString().contains("열 제한 없음"));
    }
    @Test public void serviceGreenDoesNotMakePassiveNoFixGreenOrCreateLocationRequests() {
        GnssSnapshot.phase = "RUNNING"; GnssSnapshot.mode = GnssSnapshot.MODE_PASSIVE; GnssSnapshot.running = true;
        MainActivity activity = start(); DeviceDashboard.render(activity, 100_000_000_000L);
        TextView service = activity.findViewById(R.id.cardService), location = activity.findViewById(R.id.cardLocation);
        assertEquals(DashboardStatus.Band.GREEN.color, service.getCurrentTextColor());
        assertEquals(DashboardStatus.Band.BLUE.color, location.getCurrentTextColor());
        assertTrue(location.getText().toString().contains("관찰 대기"));
        assertFalse(GnssSnapshot.observerRegistered);
        assertNull(shadowOf(activity).getNextStartedService());
    }
    @Test public void cachedMeasurementIsNotFreshBecauseItWasRecentlyDelivered() {
        MainActivity activity = start();
        GnssSnapshot.phase = "RUNNING"; GnssSnapshot.mode = GnssSnapshot.MODE_ACTIVE; GnssSnapshot.running = true;
        GnssSnapshot.lastObserverNanos = 100_000_000_000L; GnssSnapshot.lastFixElapsedRealtimeNanos = 1;
        DeviceDashboard.render(activity, 100_000_000_000L);
        TextView card = activity.findViewById(R.id.cardLocation);
        assertEquals(DashboardStatus.Band.RED.color, card.getCurrentTextColor());
        assertTrue(card.getText().toString().contains("오래된 위치"));
    }
    @Test public void allCardsHaveTextAndNetworkIsNotRepresentedAsKakaoServerHealth() {
        MainActivity activity = start(); DeviceDashboard.render(activity, 100_000_000_000L);
        int[] ids = {R.id.cardThermal,R.id.cardLocation,R.id.cardService,R.id.cardNetwork,R.id.cardPower,R.id.cardPermission};
        for (int id : ids) {
            TextView card = activity.findViewById(id);
            assertNotNull(card.getBackground()); assertFalse(card.getText().toString().isEmpty());
            assertFalse(card.getContentDescription().toString().isEmpty());
        }
        assertTrue(((TextView)activity.findViewById(R.id.cardNetwork)).getText().toString().contains("카카오 서버 접속 판정 아님"));
    }
}
