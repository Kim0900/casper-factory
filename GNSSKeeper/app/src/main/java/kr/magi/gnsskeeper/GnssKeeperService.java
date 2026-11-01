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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
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
    private HandlerThread callbackThread;
    private long lastNotificationElapsedMs = -1L;
    private volatile CsvLogger logger;
    private volatile boolean stopping = true;
    private volatile long sessionEpoch = 0L;
    private final Object sessionLock = new Object();
    private long lastEventElapsedMs = -1L;
    private LocationListener locationListener;
    private GnssStatus.Callback statusCallback;
    private GnssMeasurementsEvent.Callback measurementsCallback;


    private boolean isActiveSession(long epoch, CsvLogger sessionLogger) {
        return !stopping && epoch == sessionEpoch && sessionLogger != null
                && logger == sessionLogger;
    }

    private void onStatus(GnssStatus status, long epoch) {
        synchronized (sessionLock) {
            // Snapshot the logger once: stopTracking() may invalidate the session.
            CsvLogger sessionLogger = logger;
            if (!isActiveSession(epoch, sessionLogger)) return;
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
            if (!isActiveSession(epoch, sessionLogger)) return;
            GnssSnapshot.satellitesVisible = visible;
            GnssSnapshot.satellitesUsed = used;
            GnssSnapshot.l1Count = l1;
            GnssSnapshot.l5Count = l5;
            if (!sessionLogger.logStatus(System.currentTimeMillis(), visible, used, l1, l5,
                    maxCn0, visible == 0 ? 0f : sumCn0 / visible)) {
                if (isActiveSession(epoch, sessionLogger)) {
                    GnssSnapshot.lastError = "GNSS 상태 CSV 기록 실패";
                    stopSelf();
                }
                return;
            }
            refreshNotification();
        }
    }

    private void onMeasurements(GnssMeasurementsEvent event, long epoch) {
        synchronized (sessionLock) {
            CsvLogger sessionLogger = logger;
            if (!isActiveSession(epoch, sessionLogger)) return;
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
            if (!isActiveSession(epoch, sessionLogger)) return;
            if (!sessionLogger.logMeasurement(System.currentTimeMillis(), idx, discontinuity,
                    count, l1, l5, count == 0 ? 0f : sumCn0 / count)) {
                if (isActiveSession(epoch, sessionLogger)) {
                    GnssSnapshot.lastError = "GNSS 측정 CSV 기록 실패";
                    stopSelf();
                }
            }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        callbackThread = new HandlerThread("GNSSKeeper-callbacks", Process.THREAD_PRIORITY_BACKGROUND);
        callbackThread.start();
        Handler callbackHandler = new Handler(callbackThread.getLooper());
        executor = command -> callbackHandler.post(command);
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
        if (GnssSnapshot.running) {
            if (mode.equals(GnssSnapshot.mode)) return;
            stopTracking(); // Switching modes must unregister the previous provider/callbacks.
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            GnssSnapshot.lastError = "정확한 위치 권한 없음";
            stopSelf();
            return;
        }
        GnssSnapshot.lastError = "";
        GnssSnapshot.lastLocationElapsedMs = 0L;
        GnssSnapshot.lastFixElapsedRealtimeNanos = 0L;
        GnssSnapshot.lastGapMs = -1L;
        GnssSnapshot.accuracyM = Float.NaN;
        GnssSnapshot.speedMps = Float.NaN;
        GnssSnapshot.bearingDeg = Float.NaN;
        GnssSnapshot.satellitesVisible = 0;
        GnssSnapshot.satellitesUsed = 0;
        GnssSnapshot.l1Count = 0;
        GnssSnapshot.l5Count = 0;
        GnssSnapshot.measurementEvents = 0L;
        GnssSnapshot.clockDiscontinuity = -1;
        GnssSnapshot.statusRegistered = false;
        GnssSnapshot.measurementsRegistered = false;
        lastEventElapsedMs = -1L;
        lastNotificationElapsedMs = -1L;
        try {
            logger = new CsvLogger(this, mode);
            GnssSnapshot.logDir = logger.sessionDir.getAbsolutePath();
        } catch (IOException e) {
            GnssSnapshot.logDir = "";
            GnssSnapshot.lastError = "CSV 파일 생성 실패: " + e.getClass().getSimpleName();
            stopSelf(); // This is a measurement app; running without evidence is not success.
            return;
        }
        final long epoch = ++sessionEpoch;
        locationListener = location -> onLocation(location, epoch);
        statusCallback = new GnssStatus.Callback() {
            @Override public void onSatelliteStatusChanged(GnssStatus status) {
                onStatus(status, epoch);
            }
        };
        measurementsCallback = new GnssMeasurementsEvent.Callback() {
            @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                onMeasurements(event, epoch);
            }
        };
        stopping = false;
        try {
            if (GnssSnapshot.MODE_PASSIVE.equals(mode)) {
                LocationRequest passiveRequest = new LocationRequest.Builder(1000L)
                        .setMinUpdateIntervalMillis(0L).setMinUpdateDistanceMeters(0f).build();
                locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER, passiveRequest, executor, locationListener);
            } else {
                LocationRequest request = new LocationRequest.Builder(1000L)
                        .setMinUpdateIntervalMillis(500L).setMinUpdateDistanceMeters(0f)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, request, executor, locationListener);
                GnssSnapshot.statusRegistered =
                        locationManager.registerGnssStatusCallback(executor, statusCallback);
                GnssMeasurementRequest measurementRequest = new GnssMeasurementRequest.Builder()
                        .setFullTracking(true).build();
                GnssSnapshot.measurementsRegistered =
                        locationManager.registerGnssMeasurementsCallback(
                                measurementRequest, executor, measurementsCallback);
                if (!GnssSnapshot.statusRegistered || !GnssSnapshot.measurementsRegistered) {
                    GnssSnapshot.lastError = "일부 GNSS 계측 콜백 미등록 (위치는 기록 중)";
                }
            }
            GnssSnapshot.mode = mode;
            GnssSnapshot.running = true;
            refreshNotification();
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            GnssSnapshot.lastError = "위치 추적 시작 실패: " + e.getClass().getSimpleName();
            stopTracking();
            stopSelf();
        }
    }

    private void stopTracking() {
        // Invalidate the epoch before unregistering callbacks. Already queued
        // callbacks belong to the old epoch and cannot write into the next session.
        stopping = true;
        ++sessionEpoch;
        synchronized (sessionLock) {
            LocationListener oldLocation = locationListener;
            GnssStatus.Callback oldStatus = statusCallback;
            GnssMeasurementsEvent.Callback oldMeasurements = measurementsCallback;
            locationListener = null;
            statusCallback = null;
            measurementsCallback = null;
            CsvLogger closedLogger = logger;
            logger = null;
            if (oldLocation != null) {
                try { locationManager.removeUpdates(oldLocation); } catch (Exception ignored) {}
            }
            if (oldStatus != null) {
                try { locationManager.unregisterGnssStatusCallback(oldStatus); } catch (Exception ignored) {}
            }
            if (oldMeasurements != null) {
                try { locationManager.unregisterGnssMeasurementsCallback(oldMeasurements); } catch (Exception ignored) {}
            }
            if (closedLogger != null) closedLogger.close();
            GnssSnapshot.running = false;
            GnssSnapshot.mode = GnssSnapshot.MODE_NONE;
            GnssSnapshot.statusRegistered = false;
            GnssSnapshot.measurementsRegistered = false;
        }
    }

    private void onLocation(Location location, long epoch) {
        synchronized (sessionLock) {
            CsvLogger sessionLogger = logger;
            if (!isActiveSession(epoch, sessionLogger)) return;
            long nowElapsed = SystemClock.elapsedRealtime();
            long gapMs = lastEventElapsedMs < 0 ? -1L : (nowElapsed - lastEventElapsedMs);
            lastEventElapsedMs = nowElapsed;
            long fixAgeMs = nowElapsed - (location.getElapsedRealtimeNanos() / 1_000_000L);
            GnssSnapshot.lastLocationElapsedMs = nowElapsed;
            GnssSnapshot.lastFixElapsedRealtimeNanos = location.getElapsedRealtimeNanos();
            GnssSnapshot.lastGapMs = gapMs;
            GnssSnapshot.accuracyM = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
            GnssSnapshot.speedMps = location.hasSpeed() ? location.getSpeed() : Float.NaN;
            GnssSnapshot.bearingDeg = location.hasBearing() ? location.getBearing() : Float.NaN;
            if (!sessionLogger.logLocation(System.currentTimeMillis(), location.getElapsedRealtimeNanos(),
                    location.getLatitude(), location.getLongitude(),
                    location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                    location.hasSpeed() ? location.getSpeed() : Float.NaN,
                    location.hasBearing() ? location.getBearing() : Float.NaN,
                    location.hasAltitude() ? location.getAltitude() : Double.NaN,
                    fixAgeMs, gapMs, location.getProvider())) {
                if (isActiveSession(epoch, sessionLogger)) {
                    GnssSnapshot.lastError = "위치 CSV 기록 실패";
                    stopSelf();
                }
                return;
            }
            refreshNotification();
        }
    }

    private Notification buildNotification(String mode) {
        String modeLabel = GnssSnapshot.MODE_PASSIVE.equals(mode) ? "PASSIVE 관찰" : "ACTIVE 유지";
        String text;
        if (!GnssSnapshot.lastError.isEmpty()) {
            text = modeLabel + " · " + GnssSnapshot.lastError;
        } else if (Float.isNaN(GnssSnapshot.accuracyM)) {
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
        long now = SystemClock.elapsedRealtime();
        if (lastNotificationElapsedMs >= 0L && now - lastNotificationElapsedMs < 1000L) return;
        lastNotificationElapsedMs = now;
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
        stopTracking();
        if (callbackThread != null) callbackThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
