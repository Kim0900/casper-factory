package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** A persistent PASSIVE observer O; ACTIVE adds a separate intervention/diagnostic path. */
public final class GnssKeeperService extends Service {
    public static final String ACTION_START_ACTIVE = "kr.magi.gnsskeeper.START_ACTIVE";
    public static final String ACTION_START_PASSIVE = "kr.magi.gnsskeeper.START_PASSIVE";
    public static final String ACTION_STOP = "kr.magi.gnsskeeper.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "gnss_keeper";
    private final SessionExecutor sessions = new SessionExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Long> receipt = new ThreadLocal<>();
    private final AtomicBoolean overloaded = new AtomicBoolean();
    private final String experimentId = "run_" + UUID.randomUUID();
    private LocationManager manager;
    private Executor callbackExecutor;
    // Worker-owned fields. Main only publishes epochs/commands and reads the snapshot.
    private CsvLogger logger;
    private LocationListener observer, direct;
    private GnssStatus.Callback status;
    private GnssMeasurementsEvent.Callback measurements;
    private long activeEpoch, sequence;
    private volatile int activeStartId;
    private volatile String requestedMode = GnssSnapshot.MODE_NONE;
    private volatile boolean destroyed;
    private static volatile GnssKeeperService currentService;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (GnssSnapshot.running) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) try { nm.notify(NOTIFICATION_ID, notification()); }
                catch (SecurityException ignored) { /* Notification permission may be revoked. */ }
            }
            main.postDelayed(this, 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        currentService = this;
        GnssSnapshot.resetMeasurements();
        GnssSnapshot.observerRegistered = false;
        manager = getSystemService(LocationManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "GNSS Keeper", NotificationManager.IMPORTANCE_LOW));
        callbackExecutor = command -> {
            long e = sessions.epoch();
            long received = SystemClock.elapsedRealtimeNanos();
            boolean accepted = sessions.callback(e, () -> {
                receipt.set(received);
                try { command.run(); }
                catch (RuntimeException ex) { fail(e, "위치 콜백 처리 실패"); }
                finally { receipt.remove(); }
            });
            if (!accepted && !destroyed && overloaded.compareAndSet(false, true)) {
                GnssSnapshot.droppedCallbacks++;
                GnssSnapshot.running = false;
                GnssSnapshot.phase = "ERROR";
                GnssSnapshot.lastError = "기록 대기열 초과: 누락을 감지하여 중지 중";
                sessions.control(e, () -> fail(e, "기록 대기열 초과: 세션 불완전"));
            }
        };
        main.post(heartbeat);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        activeStartId = startId;
        if (ACTION_STOP.equals(action)) {
            long e = sessions.nextEpoch();
            requestedMode = GnssSnapshot.MODE_NONE;
            GnssSnapshot.running = false; GnssSnapshot.phase = "STOPPING";
            sessions.control(e, () -> {
                try { cleanup("USER_STOP", true); if (sessions.current(e)) GnssSnapshot.phase = "STOPPED"; }
                catch (IOException ex) { markError("세션 종료 기록 실패"); }
                finally { if (sessions.current(e)) stopSelf(startId); }
            });
            return START_NOT_STICKY;
        }
        if (!ACTION_START_ACTIVE.equals(action) && !ACTION_START_PASSIVE.equals(action)) {
            stopSelf(startId); return START_NOT_STICKY;
        }
        String mode = ACTION_START_ACTIVE.equals(action) ? GnssSnapshot.MODE_ACTIVE : GnssSnapshot.MODE_PASSIVE;
        if (mode.equals(requestedMode) && ("RUNNING".equals(GnssSnapshot.phase) || "STARTING".equals(GnssSnapshot.phase))) return START_NOT_STICKY;
        long e = sessions.nextEpoch();
        requestedMode = mode;
        GnssSnapshot.phase = "STARTING"; GnssSnapshot.running = false; GnssSnapshot.lastError = "";
        try {
            if (manager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException();
            if (!manager.isLocationEnabled()) throw new IllegalStateException();
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ex) {
            markError("시작 불가: 정확한 위치 권한·위치 설정·앱 전경 상태를 확인하세요");
            sessions.control(e, () -> {
                try { cleanup("START_REJECTED", true); } catch (IOException ignored) { }
                if (sessions.current(e)) stopSelf(startId);
            });
            return START_NOT_STICKY;
        }
        sessions.control(e, () -> {
            try { startSession(mode, e); }
            catch (IOException | RuntimeException ex) { fail(e, "세션 시작 실패: 권한·위치 설정·저장 공간을 확인하세요"); }
        });
        return START_NOT_STICKY;
    }

    private void startSession(String mode, long e) throws IOException {
        cleanup("MODE_SWITCH", false); // O remains registered across K0 -> K1 -> K0.
        if (!sessions.current(e)) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || !manager.isLocationEnabled()) throw new SecurityException();
        GnssSnapshot.resetMeasurements(); overloaded.set(false);
        long start = SystemClock.elapsedRealtimeNanos();
        File base = getExternalFilesDir("gnss_logs");
        if (base == null) base = new File(getFilesDir(), "gnss_logs");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("experiment_id", experimentId); meta.put("version_name", BuildConfig.VERSION_NAME);
        meta.put("version_code", Integer.toString(BuildConfig.VERSION_CODE));
        meta.put("device_model", Build.MODEL); meta.put("android_sdk", Integer.toString(Build.VERSION.SDK_INT));
        meta.put("observer_policy", "PASSIVE interval=1000 minInterval=0 minDistance=0; no active acquisition");
        meta.put("comparison_policy", "Quality comparisons only within identical source_provider; no Kakao delivery claim");
        logger = new CsvLogger(base, mode, e, start, meta);
        activeEpoch = e; sequence = 0;
        GnssSnapshot.logDir = logger.sessionDir.getAbsolutePath(); GnssSnapshot.mode = mode;
        if (observer == null) {
            observer = new LocationListener() {
                @Override public void onLocationChanged(Location location) { onObserved(location); }
                @Override public void onProviderDisabled(String provider) {
                    if (!valid(activeEpoch)) return;
                    GnssSnapshot.lastObserverNanos = 0; GnssSnapshot.lastFixElapsedRealtimeNanos = 0;
                    note("PROVIDER_DISABLED", provider);
                }
                @Override public void onProviderEnabled(String provider) { note("PROVIDER_ENABLED", provider); }
            };
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    new LocationRequest.Builder(1000L).setMinUpdateIntervalMillis(0L).setMinUpdateDistanceMeters(0f).build(),
                    callbackExecutor, observer);
            GnssSnapshot.observerRegistered = true;
            note("OBSERVER_REGISTERED", "passive");
        } else note("OBSERVER_RETAINED", "passive");
        if (GnssSnapshot.MODE_ACTIVE.equals(mode)) {
            direct = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (!valid(e)) return;
                    try { logger.logDirect(sample(location, 0)); } catch (IOException ex) { fail(e, "GPS 진단 기록 실패"); }
                }
                @Override public void onProviderDisabled(String provider) {
                    if (valid(e)) {
                        GnssSnapshot.lastStatusNanos = GnssSnapshot.lastMeasurementNanos = 0;
                        note("ACTIVE_PROVIDER_DISABLED", provider);
                    }
                }
            };
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    new LocationRequest.Builder(1000L).setMinUpdateIntervalMillis(500L).setMinUpdateDistanceMeters(0f)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(), callbackExecutor, direct);
            status = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus s) { if (valid(e)) onStatus(s); }
                @Override public void onStopped() {
                    if (valid(e)) { GnssSnapshot.lastStatusNanos = 0; GnssSnapshot.lastMeasurementNanos = 0; note("GNSS_STOPPED", ""); }
                }
            };
            measurements = new GnssMeasurementsEvent.Callback() {
                @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) { if (valid(e)) onMeasurements(event); }
                @Override public void onStatusChanged(int statusCode) { if (valid(e)) note("RAW_STATUS", Integer.toString(statusCode)); }
            };
            GnssSnapshot.statusRegistered = manager.registerGnssStatusCallback(callbackExecutor, status);
            GnssSnapshot.measurementsRegistered = manager.registerGnssMeasurementsCallback(
                    new GnssMeasurementRequest.Builder().setFullTracking(true).build(), callbackExecutor, measurements);
            note("ACTIVE_REGISTERED", "gps; status=" + GnssSnapshot.statusRegistered + "; raw=" + GnssSnapshot.measurementsRegistered);
            if (!GnssSnapshot.statusRegistered || !GnssSnapshot.measurementsRegistered)
                GnssSnapshot.lastError = "일부 GNSS 진단 미등록: 공통 관찰 기록은 계속됩니다";
        }
        if (sessions.current(e)) { GnssSnapshot.phase = "RUNNING"; GnssSnapshot.running = true; }
    }

    private boolean valid(long e) { return !destroyed && sessions.current(e) && e == activeEpoch && logger != null && !overloaded.get(); }
    private long received() { Long r = receipt.get(); return r == null ? SystemClock.elapsedRealtimeNanos() : r; }
    private LocationSample sample(Location l, long seq) {
        return new LocationSample(System.currentTimeMillis(), l.getElapsedRealtimeNanos(), received(), seq,
                l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : Double.NaN,
                l.hasAccuracy() ? l.getAccuracy() : Float.NaN, l.hasSpeed() ? l.getSpeed() : Float.NaN,
                l.hasBearing() ? l.getBearing() : Float.NaN, l.getProvider(), l.isMock());
    }
    private void onObserved(Location l) {
        long e = activeEpoch;
        if (!valid(e)) return;
        LocationSample s = sample(l, ++sequence);
        try {
            logger.logObserver(s); // This is the ONLY primary location writer, in BOTH modes.
            if (!valid(e)) return;
            GnssSnapshot.lastGapMs = GnssSnapshot.lastObserverNanos == 0 ? -1 : (s.receivedNanos - GnssSnapshot.lastObserverNanos) / 1_000_000L;
            GnssSnapshot.lastObserverNanos = s.receivedNanos; GnssSnapshot.lastFixElapsedRealtimeNanos = s.fixNanos;
            GnssSnapshot.accuracyM = s.accuracy; GnssSnapshot.speedMps = s.speed;
            GnssSnapshot.sourceProvider = s.sourceProvider; GnssSnapshot.observerSamples++;
        } catch (IOException ex) { fail(e, "공통 관찰 CSV 기록 실패"); }
    }
    private void onStatus(GnssStatus s) {
        final long e = activeEpoch;
        int count = s.getSatelliteCount(), used = 0, l1 = 0, l5 = 0;
        float sum = 0, max = 0;
        for (int i = 0; i < count; i++) {
            if (s.usedInFix(i)) used++;
            float cn = s.getCn0DbHz(i); sum += cn; max = Math.max(max, cn);
            if (s.hasCarrierFrequencyHz(i)) {
                float mhz = s.getCarrierFrequencyHz(i) / 1_000_000f;
                if (mhz >= 1550 && mhz <= 1610) l1++;
                if (mhz >= 1150 && mhz <= 1250) l5++;
            }
        }
        try {
            logger.logStatus(System.currentTimeMillis(), received(), count, used, l1, l5, max, count == 0 ? 0 : sum / count);
            if (!valid(e)) return;
            GnssSnapshot.lastStatusNanos = received(); GnssSnapshot.satellitesVisible = count;
            GnssSnapshot.satellitesUsed = used; GnssSnapshot.l1Count = l1; GnssSnapshot.l5Count = l5;
        } catch (IOException ex) { fail(activeEpoch, "GNSS 상태 기록 실패"); }
    }
    private void onMeasurements(GnssMeasurementsEvent event) {
        final long e = activeEpoch;
        int count = 0; float sum = 0;
        for (GnssMeasurement m : event.getMeasurements()) { count++; sum += m.getCn0DbHz(); }
        int discontinuity = event.getClock().getHardwareClockDiscontinuityCount();
        try {
            logger.logMeasurement(System.currentTimeMillis(), received(), GnssSnapshot.measurementEvents + 1,
                    discontinuity, count, count == 0 ? 0 : sum / count);
            if (!valid(e)) return;
            GnssSnapshot.lastMeasurementNanos = received(); GnssSnapshot.measurementEvents++;
            GnssSnapshot.clockDiscontinuity = discontinuity;
        } catch (IOException ex) { fail(activeEpoch, "Raw GNSS 기록 실패"); }
    }
    private void note(String event, String detail) {
        if (logger == null || !sessions.current(activeEpoch)) return;
        try { logger.event(System.currentTimeMillis(), received(), event, detail); }
        catch (IOException ex) { throw new IllegalStateException("Event write failed", ex); }
    }
    private void markError(String message) {
        GnssSnapshot.lastError = message; GnssSnapshot.phase = "ERROR"; GnssSnapshot.running = false;
    }
    private void fail(long e, String message) {
        if (!sessions.current(e)) return;
        markError(message);
        try { cleanup("ERROR", true); } catch (IOException ex) { GnssSnapshot.lastError = message + " · 종료 기록도 불완전"; }
        if (sessions.current(e)) stopSelf(activeStartId);
    }
    private void cleanup(String reason, boolean removeObserver) throws IOException {
        // All unregisters and file flush/close execute on the worker, including onDestroy.
        if (direct != null) { try { manager.removeUpdates(direct); } catch (RuntimeException ignored) { } direct = null; }
        if (status != null) { try { manager.unregisterGnssStatusCallback(status); } catch (RuntimeException ignored) { } status = null; }
        if (measurements != null) { try { manager.unregisterGnssMeasurementsCallback(measurements); } catch (RuntimeException ignored) { } measurements = null; }
        if (removeObserver && observer != null) { try { manager.removeUpdates(observer); } catch (RuntimeException ignored) { } observer = null; }
        if (!destroyed) {
            GnssSnapshot.statusRegistered = GnssSnapshot.measurementsRegistered = false;
            if (removeObserver) GnssSnapshot.observerRegistered = false;
        }
        CsvLogger old = logger; logger = null;
        if (old != null) old.finish(SystemClock.elapsedRealtimeNanos(), reason);
    }
    private Notification notification() {
        String text = GnssSnapshot.phase + " · " + requestedMode;
        if (GnssSnapshot.running) text += GnssSnapshot.fresh(GnssSnapshot.lastFixElapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()) ? " · 위치 수신 중" : " · 위치 대기/오래된 자료";
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, GnssKeeperService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("GNSS Keeper").setContentText(text).setContentIntent(open).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "중지", stop).build()).build();
    }
    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacks(heartbeat);
        GnssSnapshot.running = false; GnssSnapshot.observerRegistered = false;
        GnssSnapshot.statusRegistered = GnssSnapshot.measurementsRegistered = false;
        if (!"ERROR".equals(GnssSnapshot.phase) && !"STOPPED".equals(GnssSnapshot.phase)) GnssSnapshot.phase = "STOPPING";
        sessions.retire(() -> {
            boolean success = true;
            try { cleanup("SERVICE_DESTROYED", true); } catch (IOException ex) { success = false; }
            final boolean closed = success;
            main.post(() -> {
                if (currentService != this) return;
                if (!closed) markError("세션 종료 기록 실패: 기록이 불완전할 수 있습니다");
                else if (!"ERROR".equals(GnssSnapshot.phase)) GnssSnapshot.phase = "STOPPED";
            });
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
