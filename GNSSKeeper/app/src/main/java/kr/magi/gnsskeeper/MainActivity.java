package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10, REQ_NOTIFICATION = 11;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, detail;
    private ImageView radar;
    private String pendingMode, notice = "";
    private boolean permissionInFlight, notificationChecked, waitingSettings;
    private float angle;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 1000); }
    };
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (GnssSnapshot.running) { angle = (angle + 4.5f) % 360; radar.setRotation(angle); }
            handler.postDelayed(this, 60);
        }
    };
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_main);
        state = findViewById(R.id.txtState); detail = findViewById(R.id.txtDetail); radar = findViewById(R.id.imgRadar);
        if (saved != null) {
            pendingMode = saved.getString("pending_mode"); notice = saved.getString("notice", "");
            permissionInFlight = saved.getBoolean("permission_in_flight");
            notificationChecked = saved.getBoolean("notification_checked"); waitingSettings = saved.getBoolean("waiting_settings");
        }
        findViewById(R.id.btnStartPassive).setOnClickListener(v -> requestStart(GnssSnapshot.MODE_PASSIVE));
        findViewById(R.id.btnStartActive).setOnClickListener(v -> requestStart(GnssSnapshot.MODE_ACTIVE));
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            pendingMode = null; waitingSettings = false;
            try { startService(new Intent(this, GnssKeeperService.class).setAction(GnssKeeperService.ACTION_STOP)); }
            catch (SecurityException | IllegalStateException ex) { notice = "중지 요청 실패: 앱을 다시 열어 확인하세요"; }
            render();
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            pendingMode = null;
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException ex) { notice = "Android 설정에서 이 앱의 권한을 확인하세요"; render(); }
        });
    }
    private boolean preciseAllowed() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private boolean locationEnabled() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }
    private void requestStart(String mode) {
        if (permissionInFlight) return;
        pendingMode = mode; notice = ""; notificationChecked = false;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            pendingMode = null; notice = "GNSS를 지원하는 기기가 필요합니다"; render(); return;
        }
        if (!preciseAllowed()) {
            permissionInFlight = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        } else continueStart();
    }
    private void continueStart() {
        if (pendingMode == null || permissionInFlight) return;
        // Recheck actual permission after every dialog/settings return; grantResults[0] is insufficient.
        if (!preciseAllowed()) {
            pendingMode = null; waitingSettings = false;
            notice = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ? "대략적 위치만 허용됨: 앱 설정에서 ‘정확한 위치’를 켠 후 시작하세요"
                    : "위치 권한이 거부됨: 앱 설정에서 위치 권한을 허용하세요";
            render(); return;
        }
        if (!locationEnabled()) {
            if (waitingSettings) { pendingMode = null; waitingSettings = false; notice = "기기 위치가 꺼져 있어 시작하지 않았습니다"; render(); return; }
            waitingSettings = true; notice = "기기 위치를 켠 후 앱으로 돌아오세요";
            try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            catch (RuntimeException ex) { pendingMode = null; waitingSettings = false; notice = "기기 위치 설정을 열 수 없습니다"; }
            render(); return;
        }
        waitingSettings = false;
        if (Build.VERSION.SDK_INT >= 33 && !notificationChecked
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationChecked = true; permissionInFlight = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION); return;
        }
        String mode = pendingMode; pendingMode = null;
        // Notification denial does not prohibit a user-started location foreground service.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notice = "알림이 차단되어 알림창에 상태가 표시되지 않을 수 있습니다. 앱 화면에서 실행 상태를 확인하세요";
        try {
            if (!preciseAllowed() || !locationEnabled()) throw new SecurityException();
            startForegroundService(new Intent(this, GnssKeeperService.class).setAction(
                    GnssSnapshot.MODE_PASSIVE.equals(mode) ? GnssKeeperService.ACTION_START_PASSIVE : GnssKeeperService.ACTION_START_ACTIVE));
        } catch (SecurityException | IllegalStateException | IllegalArgumentException ex) {
            notice = "시작 요청 실패: 위치 권한·기기 위치 설정을 확인하고 앱을 연 상태에서 다시 시작하세요";
        }
        render();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_LOCATION || request == REQ_NOTIFICATION) { permissionInFlight = false; continueStart(); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pending_mode", pendingMode); out.putString("notice", notice);
        out.putBoolean("permission_in_flight", permissionInFlight); out.putBoolean("notification_checked", notificationChecked);
        out.putBoolean("waiting_settings", waitingSettings); super.onSaveInstanceState(out);
    }
    @Override protected void onResume() {
        super.onResume(); handler.post(refresh); handler.post(rotate);
        if (pendingMode != null && !permissionInFlight) continueStart();
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); handler.removeCallbacks(rotate); super.onPause(); }
    private static String age(long timestamp, long now) { return timestamp <= 0 ? "수신 없음" : String.format(Locale.KOREA, "%.1f초 전", Math.max(0, now - timestamp) / 1e9); }
    private void render() {
        long now = SystemClock.elapsedRealtimeNanos();
        boolean active = GnssSnapshot.MODE_ACTIVE.equals(GnssSnapshot.mode);
        boolean freshFix = GnssSnapshot.running && GnssSnapshot.fresh(GnssSnapshot.lastFixElapsedRealtimeNanos, now);
        boolean freshStatus = GnssSnapshot.running && active && GnssSnapshot.fresh(GnssSnapshot.lastStatusNanos, now);
        boolean freshRaw = GnssSnapshot.running && active && GnssSnapshot.fresh(GnssSnapshot.lastMeasurementNanos, now);
        String phase;
        switch (GnssSnapshot.phase) {
            case "RUNNING": phase = "실행 중"; break;
            case "STARTING": phase = "시작 준비 중"; break;
            case "STOPPING": phase = "기록 종료 중"; break;
            case "ERROR": phase = "오류로 중지"; break;
            default: phase = "중지";
        }
        state.setText("상태: " + phase + " · " + GnssSnapshot.mode + " · v" + BuildConfig.VERSION_NAME);
        StringBuilder text = new StringBuilder();
        if (!notice.isEmpty()) text.append(notice).append("\n\n");
        if (!GnssSnapshot.lastError.isEmpty()) text.append(GnssSnapshot.lastError).append("\n\n");
        text.append("공통 관찰: PASSIVE · ").append(GnssSnapshot.observerRegistered ? "등록됨" : "미등록").append("\n")
                .append("원 공급자: ").append(GnssSnapshot.sourceProvider.isEmpty() ? "N/A" : GnssSnapshot.sourceProvider).append("\n")
                .append("마지막 수신: ").append(age(GnssSnapshot.lastObserverNanos, now)).append("\n")
                .append("위치 측정 시각: ").append(age(GnssSnapshot.lastFixElapsedRealtimeNanos, now)).append("\n")
                .append("추정 정확도: ").append(freshFix && !Float.isNaN(GnssSnapshot.accuracyM) ? String.format(Locale.KOREA, "%.1f m", GnssSnapshot.accuracyM) : "N/A (미수신 또는 오래된 자료)").append("\n")
                .append("수신 간격: ").append(GnssSnapshot.lastGapMs < 0 ? "N/A" : GnssSnapshot.lastGapMs + " ms").append("\n")
                .append("공통 관찰 표본: ").append(GnssSnapshot.observerSamples).append("\n\n")
                .append("ACTIVE 진단 · 위성 상태: ").append(GnssSnapshot.statusRegistered ? "등록됨" : "미등록").append("\n")
                .append("신호 항목(사용/관측): ").append(freshStatus ? GnssSnapshot.satellitesUsed + "/" + GnssSnapshot.satellitesVisible : "N/A").append("\n")
                .append("L1/L5 유사 대역 항목: ").append(freshStatus ? GnssSnapshot.l1Count + "/" + GnssSnapshot.l5Count : "N/A").append("\n")
                .append("위성 상태 수신: ").append(age(GnssSnapshot.lastStatusNanos, now)).append("\n")
                .append("Raw 콜백: ").append(GnssSnapshot.measurementsRegistered ? "등록됨" : "미등록").append(" · ")
                .append(freshRaw ? "수신 중" : "N/A (미수신/오래됨)").append("\n")
                .append("Raw 수신: ").append(age(GnssSnapshot.lastMeasurementNanos, now)).append(" · 누적 ").append(GnssSnapshot.measurementEvents).append("회\n\n")
                .append("신선도 표시 기준: 5초. PASSIVE 무수신 시 ACTIVE로 자동 전환하지 않습니다.\n")
                .append("신호 항목 수는 고유 위성 수가 아니며, 추정 정확도는 실제 오차 측정값이 아닙니다.\n")
                .append("기록 경로: ").append(GnssSnapshot.logDir.isEmpty() ? "아직 없음" : GnssSnapshot.logDir);
        detail.setText(text.toString());
    }
}
