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

/**
 * v0.2(2026-09-09) — CASPER 작업요청 반영. PASSIVE 관찰모드 신설:
 * Keeper 자체가 GPS_PROVIDER를 요청하지 않고, PASSIVE_PROVIDER로
 * "다른 앱/시스템이 만든 위치 업데이트"만 수신한다. GnssStatus·
 * GnssMeasurements 콜백은 그 등록 행위 자체가 GNSS 개입 소지가
 * 있다고 판단해 PASSIVE 모드에서는 등록하지 않는다(요청서 "setFullTracking(true)
 * 사용 금지" 원칙을 콜백 자체 미등록으로 안전하게 확장 적용).
 * ACTIVE 모드는 기존 v0.1.0 로직을 그대로 보존한다.
 */
public final class GnssKeeperService extends Service {
    public static final String ACTION_START_ACTIVE = "kr.magi.gnsskeeper.START_ACTIVE";
    public static final String ACTION_START_PASSIVE = "kr.magi.gnsskeeper.START_PASSIVE";
    public static final String ACTION_STOP = "kr.magi.gnsskeeper.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "gnss_keeper";

    private LocationManager locationManager;
    private Executor executor;
    private CsvLogger logger;
    private long lastEventElapsedMs = -1L;

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
        String action = intent == null ? ACTION_START_ACTIVE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String mode = ACTION_START_PASSIVE.equals(action) ? GnssSnapshot.MODE_PASSIVE : GnssSnapshot.MODE_ACTIVE;
        startForeground(NOTIFICATION_ID, buildNotification(mode), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        startTracking(mode);
        return START_NOT_STICKY;
    }

    private void startTracking(String mode) {
        if (GnssSnapshot.running) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        try {
            logger = new CsvLogger(this, mode);
            GnssSnapshot.logDir = logger.sessionDir.getAbsolutePath();
        } catch (IOException e) {
            GnssSnapshot.logDir = "로그 생성 실패: " + e.getMessage();
        }

        lastEventElapsedMs = -1L;

        if (GnssSnapshot.MODE_PASSIVE.equals(mode)) {
            // PASSIVE: Keeper 자체는 위치를 요청하지 않는다. 다른 앱/시스템이
            // 이미 만든 위치만 수동적으로 수신한다. GnssStatus/Measurements도
            // 등록하지 않는다(요청서 "setFullTracking(true) 사용 금지" 원칙).
            LocationRequest passiveRequest = new LocationRequest.Builder(1000L)
                    .setMinUpdateIntervalMillis(0L)
                    .setMinUpdateDistanceMeters(0f)
                    .build();
            locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, passiveRequest, executor, locationListener);
        } else {
            // ACTIVE: 기존 v0.1.0 로직 그대로.
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
        }

        GnssSnapshot.mode = mode;
        GnssSnapshot.running = true;
        refreshNotification();
    }

    private void onLocation(Location location) {
        long nowElapsed = SystemClock.elapsedRealtime();
        long gapMs = lastEventElapsedMs < 0 ? -1L : (nowElapsed - lastEventElapsedMs);
        lastEventElapsedMs = nowElapsed;

        long fixAgeMs = nowElapsed - (location.getElapsedRealtimeNanos() / 1_000_000L);

        GnssSnapshot.lastLocationElapsedMs = nowElapsed;
        GnssSnapshot.lastGapMs = gapMs;
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
                    fixAgeMs, gapMs, location.getProvider());
        }
        refreshNotification();
    }

    private Notification buildNotification(String mode) {
        String modeLabel = GnssSnapshot.MODE_PASSIVE.equals(mode) ? "PASSIVE 관찰" : "ACTIVE 유지";
        String text;
        if (Float.isNaN(GnssSnapshot.accuracyM)) {
            text = modeLabel + " · 위치 대기 중";
        } else {
            text = String.format(java.util.Locale.KOREA,
                    "%s · 정확도 %.0fm · 사용위성 %d/%d",
                    modeLabel, GnssSnapshot.accuracyM, GnssSnapshot.satellitesUsed, GnssSnapshot.satellitesVisible);
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
        if (nm != null && GnssSnapshot.running) nm.notify(NOTIFICATION_ID, buildNotification(GnssSnapshot.mode));
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
        GnssSnapshot.mode = GnssSnapshot.MODE_NONE;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
