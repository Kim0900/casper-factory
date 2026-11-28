package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
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
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A persistent PASSIVE observer O; ACTIVE adds a separate intervention/diagnostic path.
 * v0.3: observation summaries (§3) and automatic recovery after OS kills (§18). Request parameters
 * come only from {@link RequestSpec} and are identical for manual start and automatic restart.
 */
public final class GnssKeeperService extends Service {
    public static final String ACTION_START_ACTIVE = "kr.magi.gnsskeeper.START_ACTIVE";
    public static final String ACTION_START_PASSIVE = "kr.magi.gnsskeeper.START_PASSIVE";
    public static final String ACTION_STOP = "kr.magi.gnsskeeper.STOP";
    public static final String EXTRA_CAUSE = "kr.magi.gnsskeeper.CAUSE";
    public static final String EXTRA_RESTORE_MODE = "kr.magi.gnsskeeper.RESTORE_MODE";
    static final String CAUSE_MANUAL = "MANUAL", CAUSE_AUTO = "AUTO_RESTART", CAUSE_NOTIFICATION = "NOTIFICATION";
    private static final int NOTIFICATION_ID = 1001, ALERT_NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "gnss_keeper", ALERT_CHANNEL_ID = "gnss_keeper_alert";
    private static final long FIRST_FIX_MAX_AGE_MS = 5_000L;
    private static final int PERSIST_EVERY_TICKS = 10; // 10 s heartbeat into recovery state

    /** Everything known about why/how a session starts; built on main, handed to the worker. */
    static final class StartContext {
        final String cause;
        final RecoveryState prev;
        final ExitReasons.Record exit;
        final boolean gapExpected;
        final int attempt;
        final List<String[]> notes = new ArrayList<>();
        StartContext(String cause, RecoveryState prev, ExitReasons.Record exit, boolean gapExpected, int attempt) {
            this.cause = cause; this.prev = prev; this.exit = exit; this.gapExpected = gapExpected; this.attempt = attempt;
        }
    }

    private final Object recoveryLock = new Object();
    private RecoveryState.Store store;
    private RecoveryState rstate;
    private int heartbeatTicks;
    // Worker-owned recovery/observation fields.
    private StartContext startContext;
    private long registeredElapsedNanos;
    private boolean firstFixSeen;
    private final ConstellationSummary constellationSummary = new ConstellationSummary();
    private long lastClockTimeNanos = Long.MIN_VALUE;
    private int lastClockDiscontinuity = Integer.MIN_VALUE;
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
                if (++heartbeatTicks % PERSIST_EVERY_TICKS == 0) persistAlive();
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
        store = new RecoveryStore(this);
        synchronized (recoveryLock) { rstate = store.load(); }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "GNSS Keeper", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL_ID, "GNSS Keeper 자동 복구 알림", NotificationManager.IMPORTANCE_DEFAULT));
        }
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
        if (intent == null) return onSystemRestart(startId); // §18-1/§18-2: system STICKY re-creation
        if (ACTION_STOP.equals(action)) {
            synchronized (recoveryLock) { RecoveryPolicy.onUserStop(store, rstate); } // run_intent=false first
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
        String cause = CAUSE_NOTIFICATION.equals(intent.getStringExtra(EXTRA_CAUSE)) ? CAUSE_NOTIFICATION : CAUSE_MANUAL;
        if (mode.equals(requestedMode) && ("RUNNING".equals(GnssSnapshot.phase) || "STARTING".equals(GnssSnapshot.phase))) {
            long e = sessions.epoch(); // §18-6: idempotent — no second session
            sessions.control(e, () -> note("DUPLICATE_START", "mode=" + mode + ";cause=" + cause));
            return START_STICKY;
        }
        boolean liveInProcess = !GnssSnapshot.MODE_NONE.equals(requestedMode);
        StartContext ctx;
        boolean committed;
        synchronized (recoveryLock) {
            RecoveryState prev = rstate.copy();
            ExitReasons.Record exit = liveInProcess ? null : matchExit(prev);
            if (exit != null) rstate.markSeen(exit.key());
            boolean userStopped = exit != null && exit.kind() == ExitReasons.Kind.USER_STOP;
            ctx = new StartContext(cause, prev, exit, !liveInProcess && prev.runIntent && !prev.sessionClosed && !userStopped, 0);
            addExitNotes(ctx);
            committed = RecoveryPolicy.onManualStart(store, rstate, mode); // the only halt reset
        }
        if (!committed) ctx.notes.add(new String[]{"RECOVERY_STATE_COMMIT_FAIL", "auto_restart=disabled_for_session"});
        return beginStart(mode, ctx, startId, committed);
    }

    /** §18-6 restore order. Nothing touches foreground service or location before the attempt is persisted. */
    private int onSystemRestart(int startId) {
        StartContext ctx;
        RecoveryPolicy.Outcome outcome;
        String mode;
        synchronized (recoveryLock) {
            rstate = store.load();
            RecoveryState prev = rstate.copy();
            ExitReasons.Record exit = matchExit(prev);
            if (exit != null) rstate.markSeen(exit.key());
            ExitReasons.Kind kind = exit == null ? ExitReasons.Kind.UNKNOWN : exit.kind();
            outcome = RecoveryPolicy.onSystemRestart(store, rstate, kind, bootCount(), SystemClock.elapsedRealtime());
            long now = System.currentTimeMillis();
            ctx = new StartContext(CAUSE_AUTO, prev, exit, !prev.sessionClosed, rstate.history.size());
            addExitNotes(ctx);
            if (outcome == RecoveryPolicy.Outcome.SKIP_USER_STOP || outcome == RecoveryPolicy.Outcome.HALT_LOOP
                    || outcome == RecoveryPolicy.Outcome.HALT_COMMIT_FAIL) {
                for (String[] n : ctx.notes) rstate.addPending(now, n[0], n[1]);
                if (outcome != RecoveryPolicy.Outcome.SKIP_USER_STOP)
                    rstate.addPending(now, "RESTART_HALTED", "reason=" + (outcome == RecoveryPolicy.Outcome.HALT_LOOP ? "LOOP" : "PREFS_COMMIT_FAIL")
                            + ";window_min=10;max_restarts=" + RecoveryPolicy.MAX_RESTARTS_IN_WINDOW
                            + ";consecutive_failures=" + rstate.consecutiveFailures);
                store.commit(rstate);
            }
            mode = prev.lastMode;
        }
        if (outcome == RecoveryPolicy.Outcome.HALT_LOOP || outcome == RecoveryPolicy.Outcome.HALT_COMMIT_FAIL)
            postAlert("자동 복구 반복 실패 — 앱을 열어 확인", "자동 재시작을 멈췄습니다. 앱에서 직접 시작하면 다시 켜집니다.", null);
        boolean validMode = GnssSnapshot.MODE_ACTIVE.equals(mode) || GnssSnapshot.MODE_PASSIVE.equals(mode);
        if (outcome != RecoveryPolicy.Outcome.RESTORE || !validMode) {
            stopSelf(startId); return START_NOT_STICKY;
        }
        return beginStart(mode, ctx, startId, true);
    }

    private int beginStart(String mode, StartContext ctx, int startId, boolean sticky) {
        long e = sessions.nextEpoch();
        requestedMode = mode;
        GnssSnapshot.phase = "STARTING"; GnssSnapshot.running = false; GnssSnapshot.lastError = "";
        ctx.notes.add(0, new String[]{"SERVICE_STARTED", "cause=" + ctx.cause + ";intent=" + (CAUSE_AUTO.equals(ctx.cause) ? "null" : "START_" + mode)});
        try {
            if (manager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException();
            if (!manager.isLocationEnabled()) throw new IllegalStateException();
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ex) {
            markError("시작 불가: 정확한 위치 권한·위치 설정·앱 전경 상태를 확인하세요");
            if (CAUSE_AUTO.equals(ctx.cause)) {
                String reason = ex instanceof SecurityException ? "LOCATION_DENIED" : "FGS_DENIED";
                synchronized (recoveryLock) {
                    long now = System.currentTimeMillis();
                    for (String[] n : ctx.notes) rstate.addPending(now, n[0], n[1]);
                    rstate.addPending(now, "RESTART_BLOCKED", "reason=" + reason + ";exception=" + ex.getClass().getSimpleName()
                            + ";notification=" + notificationsAllowed());
                    store.commit(rstate);
                }
                postAlert("GNSS Keeper가 꺼졌습니다 — 눌러서 다시 켜기", "자동 복구가 막혔습니다. 누르면 앱이 열리고 이전 모드로 다시 시작합니다.", mode);
            }
            sessions.control(e, () -> {
                try { cleanup("START_REJECTED", true); } catch (IOException ignored) { }
                if (sessions.current(e)) stopSelf(startId);
            });
            return START_NOT_STICKY;
        }
        ctx.notes.add(new String[]{"FGS_STARTED", "type=location"});
        sessions.control(e, () -> {
            try { startContext = ctx; startSession(mode, e); }
            catch (IOException | RuntimeException ex) { fail(e, "세션 시작 실패: 권한·위치 설정·저장 공간을 확인하세요"); }
        });
        main.postDelayed(() -> sessions.control(e, () -> successCheck(e)), RecoveryPolicy.SUCCESS_AFTER_MS);
        return sticky ? START_STICKY : START_NOT_STICKY;
    }

    private void addExitNotes(StartContext ctx) {
        ExitReasons.Record x = ctx.exit;
        if (x == null) return;
        ctx.notes.add(new String[]{"PREV_EXIT", "reason=" + ExitReasons.name(x.reason) + ";pid=" + x.pid + ";exit_wall_ms=" + x.timestampMs
                + ";matched_session=" + (ctx.prev.sessionId.isEmpty() ? "NONE" : ctx.prev.sessionId) + ";desc=" + shortDesc(x.description)});
        if (x.kind() == ExitReasons.Kind.USER_STOP) ctx.notes.add(new String[]{"USER_STOP_EXTERNAL", "run_intent=false"});
    }

    private static String shortDesc(String d) {
        String c = d == null ? "" : d.replace(';', ',').replace('\n', ' ');
        return c.length() > 80 ? c.substring(0, 80) : c;
    }

    /** ApplicationExitInfo of the previous process (pid + time window), skipping already-processed ones. */
    private ExitReasons.Record matchExit(RecoveryState prev) {
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            if (am == null || prev.pid <= 0) return null;
            List<ExitReasons.Record> records = new ArrayList<>();
            for (ApplicationExitInfo info : am.getHistoricalProcessExitReasons(getPackageName(), 0, 5))
                records.add(new ExitReasons.Record(info.getPid(), info.getTimestamp(), info.getReason(),
                        info.getDescription() == null ? "" : info.getDescription()));
            return ExitReasons.match(records, prev.pid, prev.procStartWallMs, System.currentTimeMillis(), prev.exitSeen);
        } catch (RuntimeException ex) { return null; } // unknown reason → handled as UNKNOWN, never as user stop
    }

    private int bootCount() {
        try { return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (RuntimeException ex) { return -1; }
    }

    private void persistAlive() {
        synchronized (recoveryLock) {
            if (rstate == null) return;
            rstate.lastAliveWallMs = System.currentTimeMillis();
            rstate.lastAliveElapsedMs = SystemClock.elapsedRealtime();
            rstate.lastAliveBoot = bootCount();
            store.commit(rstate);
        }
    }

    private boolean notificationsAllowed() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    private void postAlert(String title, String text, String restoreMode) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (restoreMode != null) open.putExtra(EXTRA_RESTORE_MODE, restoreMode);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            nm.notify(ALERT_NOTIFICATION_ID, new Notification.Builder(this, ALERT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(text)
                    .setContentIntent(pi).setAutoCancel(true).build());
        } catch (SecurityException ignored) { /* Notification permission revoked: dashboard shows the warning. */ }
    }

    /** Worker: 60 s after start. AUTO restart counts as success only with a first valid fix. */
    private void successCheck(long e) {
        if (!valid(e) || startContext == null || !CAUSE_AUTO.equals(startContext.cause)) return;
        if (firstFixSeen) { synchronized (recoveryLock) { RecoveryPolicy.onRestoreSucceeded(store, rstate); } }
        else note("RESTART_BLOCKED", "reason=NO_FIX;after_s=60;session_continues=1");
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
        StartContext ctx = startContext;
        String cause = ctx == null ? CAUSE_MANUAL : ctx.cause;
        meta.put("start_cause", cause);
        meta.put("restart_of", ctx == null || ctx.prev.sessionId.isEmpty() ? "" : ctx.prev.sessionId);
        List<RequestSpec> plan = RequestSpec.forStart(mode, cause);
        meta.put("request_plan", plan.toString().replace(',', ' '));
        logger = new CsvLogger(base, mode, e, start, meta);
        activeEpoch = e; sequence = 0; firstFixSeen = false; registeredElapsedNanos = Long.MAX_VALUE;
        lastClockTimeNanos = Long.MIN_VALUE; lastClockDiscontinuity = Integer.MIN_VALUE;
        recordSessionStart(ctx);
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
            RequestSpec po = RequestSpec.PASSIVE_OBSERVER;
            manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    new LocationRequest.Builder(po.intervalMs).setMinUpdateIntervalMillis(po.minIntervalMs)
                            .setMinUpdateDistanceMeters(po.minDistanceM).build(),
                    callbackExecutor, observer);
            GnssSnapshot.observerRegistered = true;
            note("OBSERVER_REGISTERED", "passive");
        } else note("OBSERVER_RETAINED", "passive");
        if (plan.contains(RequestSpec.ACTIVE_GPS)) {
            direct = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (!valid(e)) return;
                    LocationSample ds = sample(location, 0);
                    try { logger.logDirect(ds); maybeFirstFix(e, ds); } catch (IOException ex) { fail(e, "GPS 진단 기록 실패"); }
                }
                @Override public void onProviderDisabled(String provider) {
                    if (valid(e)) {
                        GnssSnapshot.lastStatusNanos = GnssSnapshot.lastMeasurementNanos = 0;
                        note("ACTIVE_PROVIDER_DISABLED", provider);
                    }
                }
            };
            RequestSpec g = RequestSpec.ACTIVE_GPS;
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    new LocationRequest.Builder(g.intervalMs).setMinUpdateIntervalMillis(g.minIntervalMs).setMinUpdateDistanceMeters(g.minDistanceM)
                            .setQuality(g.highAccuracy ? LocationRequest.QUALITY_HIGH_ACCURACY : LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                            .build(), callbackExecutor, direct);
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
            GnssMeasurementRequest raw = new GnssMeasurementRequest.Builder()
                    .setFullTracking(RequestSpec.GNSS_MEASUREMENTS.fullTracking).build();
            GnssSnapshot.measurementsRegistered = manager.registerGnssMeasurementsCallback(raw, callbackExecutor, measurements);
            note("FULL_TRACKING_REQUESTED", "is_full_tracking=" + raw.isFullTracking()); // request value only, not chipset state
            note("ACTIVE_REGISTERED", "gps; status=" + GnssSnapshot.statusRegistered + "; raw=" + GnssSnapshot.measurementsRegistered);
            if (!GnssSnapshot.statusRegistered || !GnssSnapshot.measurementsRegistered)
                GnssSnapshot.lastError = "일부 GNSS 진단 미등록: 공통 관찰 기록은 계속됩니다";
        }
        registeredElapsedNanos = SystemClock.elapsedRealtimeNanos();
        note("LOCATION_REGISTERED", plan.toString().replace(',', ' '));
        if (sessions.current(e)) { GnssSnapshot.phase = "RUNNING"; GnssSnapshot.running = true; }
    }

    /** Worker: persist the new process/session identity, flush deferred events and start notes, log GNSS hardware once. */
    private void recordSessionStart(StartContext ctx) {
        long nowWall = System.currentTimeMillis(), nowElapsed = SystemClock.elapsedRealtime();
        List<String> deferred;
        synchronized (recoveryLock) {
            deferred = new ArrayList<>(rstate.pending);
            rstate.pending.clear();
            rstate.sessionId = logger.sessionId; rstate.pid = Process.myPid(); rstate.bootCount = bootCount();
            rstate.procStartElapsedMs = Process.getStartElapsedRealtime();
            rstate.procStartWallMs = nowWall - (nowElapsed - rstate.procStartElapsedMs);
            rstate.lastAliveWallMs = nowWall; rstate.lastAliveElapsedMs = nowElapsed; rstate.lastAliveBoot = rstate.bootCount;
            rstate.sessionClosed = false;
            if (!store.commit(rstate)) note("RECOVERY_STATE_COMMIT_FAIL", "stage=session_start");
        }
        for (String d : deferred) {
            String[] p = d.split("\\|", 3);
            if (p.length != 3) continue;
            try { logger.event(Long.parseLong(p[0]), 0L, p[1], p[2] + ";deferred=1"); }
            catch (NumberFormatException | IOException ex) { note("DEFERRED_EVENT_DROPPED", p[1]); }
        }
        if (ctx != null) for (String[] n : ctx.notes) note(n[0], n[1]);
        try {
            note("GNSS_HW", "model=" + manager.getGnssHardwareModelName() + ";year=" + manager.getGnssYearOfHardware()
                    + ";sdk=" + Build.VERSION.SDK_INT);
            note("GNSS_CAPABILITIES", String.valueOf(manager.getGnssCapabilities()).replace(',', ' '));
        } catch (RuntimeException ex) { note("GNSS_CAPABILITIES", "unavailable=" + ex.getClass().getSimpleName()); }
    }

    /** Worker: §18-4 "on" = first valid fix after registration (fresh, with accuracy) — never the first log line. */
    private void maybeFirstFix(long e, LocationSample s) {
        StartContext ctx = startContext;
        if (firstFixSeen || !valid(e) || s.fixNanos < registeredElapsedNanos) return;
        if (s.ageMs() > FIRST_FIX_MAX_AGE_MS || Float.isNaN(s.accuracy)) return;
        firstFixSeen = true;
        note("FIRST_FIX", String.format(Locale.ROOT, "fix_elapsed=%d;age_ms=%d;accuracy_m=%.1f;source=%s",
                s.fixNanos, s.ageMs(), s.accuracy, s.sourceProvider));
        if (ctx == null) return;
        if (ctx.gapExpected) {
            RecoveryState p = ctx.prev;
            RecoveryGap gap = RecoveryGap.compute(p.lastAliveBoot, p.lastAliveWallMs, p.lastAliveElapsedMs,
                    ctx.exit == null ? 0L : ctx.exit.timestampMs, bootCount(), System.currentTimeMillis(), SystemClock.elapsedRealtime());
            note("RECOVERY_GAP", gap.detail(ctx.cause.equals(CAUSE_AUTO) ? "AUTO" : ctx.cause, false));
        }
        if (CAUSE_AUTO.equals(ctx.cause))
            note("AUTO_RESTART", "mode=" + GnssSnapshot.mode + ";restart_of=" + ctx.prev.sessionId + ";attempt=" + ctx.attempt);
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
            maybeFirstFix(e, s);
            if (logger.capped()) GnssSnapshot.lastError = "로그 상한(20 MB) 도달: 요약 기록 중지 · 위치 서비스는 계속";
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
        int[] cons = new int[count]; float[] freq = new float[count], cn0 = new float[count], bb = new float[count];
        boolean[] inFix = new boolean[count];
        for (int i = 0; i < count; i++) {
            cons[i] = s.getConstellationType(i); inFix[i] = s.usedInFix(i); cn0[i] = s.getCn0DbHz(i);
            freq[i] = s.hasCarrierFrequencyHz(i) ? s.getCarrierFrequencyHz(i) : Float.NaN;
            bb[i] = s.hasBasebandCn0DbHz(i) ? s.getBasebandCn0DbHz(i) : Float.NaN;
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
            if (constellationSummary.due(received()))
                logger.logConstellation(System.currentTimeMillis(), received(), ConstellationSummary.rows(cons, freq, inFix, cn0, bb));
            if (!valid(e)) return;
            GnssSnapshot.lastStatusNanos = received(); GnssSnapshot.satellitesVisible = count;
            GnssSnapshot.satellitesUsed = used; GnssSnapshot.l1Count = l1; GnssSnapshot.l5Count = l5;
        } catch (IOException ex) { fail(activeEpoch, "GNSS 상태 기록 실패"); }
    }
    private void onMeasurements(GnssMeasurementsEvent event) {
        final long e = activeEpoch;
        int count = 0, l1 = 0, l5 = 0, adrValid = 0, multipath = 0; float sum = 0;
        for (GnssMeasurement m : event.getMeasurements()) {
            count++; sum += m.getCn0DbHz();
            if ((m.getAccumulatedDeltaRangeState() & GnssMeasurement.ADR_STATE_VALID) != 0) adrValid++;
            if (m.getMultipathIndicator() == GnssMeasurement.MULTIPATH_INDICATOR_DETECTED) multipath++;
            if (m.hasCarrierFrequencyHz()) {
                float mhz = m.getCarrierFrequencyHz() / 1_000_000f;
                if (mhz >= 1550 && mhz <= 1610) l1++;
                if (mhz >= 1150 && mhz <= 1250) l5++;
            }
        }
        int discontinuity = event.getClock().getHardwareClockDiscontinuityCount();
        long clockNanos = event.getClock().getTimeNanos();
        long intervalMs = lastClockTimeNanos == Long.MIN_VALUE || discontinuity != lastClockDiscontinuity
                ? -1L : (clockNanos - lastClockTimeNanos) / 1_000_000L; // -1: first event or clock discontinuity
        lastClockTimeNanos = clockNanos; lastClockDiscontinuity = discontinuity;
        try {
            logger.logMeasurement(System.currentTimeMillis(), received(), GnssSnapshot.measurementEvents + 1,
                    discontinuity, count, l1, l5, count == 0 ? 0 : sum / count, adrValid, multipath, intervalMs);
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
