package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.IBinder;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.Executor;

public final class GnssKeeperService extends Service {
    public static final String ACTION_START = "kr.magi.gnsskeeper.START";
    public static final String ACTION_STOP = "kr.magi.gnsskeeper.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "gnss_keeper";

    private LocationManager locationManager;
    private Executor executor;
    private CsvLogger logger;

    private final LocationListener locationListener = this::onLocation;

    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int visible = status.getSatelliteCount();
            int used = 0, l1 = 0, l5 = 0;
            float maxCn0 = 0f, sumCn0 = 0f;
            for (int i = 0; i < visible; i++) {
                if (status.usedInFix(i)) used++;
                float cn0 = status.getCn0DbHz(i);
                sumCn0 += cn0;
                if (cn0 > maxCn0) maxCn0 = cn0;
                if (status.hasCarrierFrequencyHz(i)) {
                    float mhz = status.getCarrierFrequencyHz(i) / 1_000_000f;
                    if (mhz >= 1550f && mhz <= 1610f) l1++;
                    if (mhz >= 1150f && mhz <= 1250f) l5++;
                }
            }
            GnssSnapshot.satellitesVisible = visible;
            GnssSnapshot.satellitesUsed = used;
            GnssSnapshot.l1Count = l1;
            GnssSnapshot.l5Count = l5;
            if (logger != null) logger.logStatus(System.currentTimeMillis(), visible, used, l1, l5,
                    maxCn0, visible == 0 ? 0f : sumCn0 / visible);
            refreshNotification();
        }
    };

    private final GnssMeasurementsEvent.Callback measurementsCallback = new GnssMeasurementsEvent.Callback() {
        @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            long idx = ++GnssSnapshot.measurementEvents;
            GnssClock clock = event.getClock();
            int discontinuity = clock.getHardwareClockDiscontinuityCount();
            GnssSnapshot.clockDiscontinuity = discontinuity;
            int count = 0, l1 = 0, l5 = 0;
            float sumCn0 = 0f;
            for (GnssMeasurement m : event.getMeasurements()) {
                count++;
                sumCn0 += m.getCn0DbHz();
                if (m.hasCarrierFrequencyHz()) {
                    float mhz = m.getCarrierFrequencyHz() / 1_000_000f;
                    if (mhz >= 1550f && mhz <= 1610f) l1++;
                    if (mhz >= 1150f && mhz <= 1250f) l5++;
                }
            }
            if (logger != null) logger.logMeasurement(System.currentTimeMillis(), idx, discontinuity,
                    count, l1, l5, count == 0 ? 0f : sumCn0 / count);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        executor = getMainExecutor();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        startTracking();
        return START_NOT_STICKY;
    }

    private void startTracking() {
        if (GnssSnapshot.running) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        try {
            logger = new CsvLogger(this);
            GnssSnapshot.logDir = logger.sessionDir.getAbsolutePath();
        } catch (IOException e) {
            GnssSnapshot.logDir = "로그 생성 실패: " + e.getMessage();
        }

        LocationRequest request = new LocationRequest.Builder(1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0f)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build();
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, executor, locationListener);
        locationManager.registerGnssStatusCallback(executor, statusCallback);

        GnssMeasurementRequest measurementRequest = new GnssMeasurementRequest.Builder()
                .setFullTracking(true)
                .build();
        locationManager.registerGnssMeasurementsCallback(measurementRequest, executor, measurementsCallback);

        GnssSnapshot.running = true;
        refreshNotification();
    }

    private void onLocation(Location location) {
        GnssSnapshot.lastLocationElapsedMs = SystemClock.elapsedRealtime();
        GnssSnapshot.accuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        GnssSnapshot.speedMps = location.hasSpeed() ? location.getSpeed() : Float.NaN;
        GnssSnapshot.bearingDeg = location.hasBearing() ? location.getBearing() : Float.NaN;
        if (logger != null) {
            logger.logLocation(System.currentTimeMillis(), location.getElapsedRealtimeNanos(),
                    location.getLatitude(), location.getLongitude(),
                    location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                    location.hasSpeed() ? location.getSpeed() : Float.NaN,
                    location.hasBearing() ? location.getBearing() : Float.NaN,
                    location.hasAltitude() ? location.getAltitude() : Double.NaN,
                    location.getProvider());
        }
        refreshNotification();
    }

    private Notification buildNotification() {
        String text;
        if (Float.isNaN(GnssSnapshot.accuracyM)) {
            text = "고정밀 GNSS 위치 요청 중";
        } else {
            text = String.format(java.util.Locale.KOREA,
                    "정확도 %.0fm · 사용위성 %d/%d",
                    GnssSnapshot.accuracyM, GnssSnapshot.satellitesUsed, GnssSnapshot.satellitesVisible);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("GNSS Keeper 실행 중")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void refreshNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && GnssSnapshot.running) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GNSS Keeper", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("GNSS 유지 서비스 상태");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        try { locationManager.unregisterGnssStatusCallback(statusCallback); } catch (Exception ignored) {}
        try { locationManager.unregisterGnssMeasurementsCallback(measurementsCallback); } catch (Exception ignored) {}
        if (logger != null) logger.close();
        GnssSnapshot.running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
