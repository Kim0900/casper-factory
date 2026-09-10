package kr.magi.gnsskeeper;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Locale;

/**
 * v0.2(2026-09-09) — 관찰만 시작(PASSIVE)/GNSS 유지 시작(ACTIVE)/중지
 * 3버튼 구조. 대표님 요청(2026-09-09) 반영: 실행 중일 때만 회전하고
 * 중지 시 그 자리에서 멈추는 레이더 스캐너 표시(순수 VectorDrawable+
 * ObjectAnimator, 외부 이미지/라이브러리 불필요).
 */
public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private static final long RADAR_ROTATION_MS = 2400L;

    private TextView txtState;
    private TextView txtDetail;
    private ImageView imgRadar;
    private ObjectAnimator radarAnimator;
    private String pendingMode = GnssSnapshot.MODE_ACTIVE;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtState = findViewById(R.id.txtState);
        txtDetail = findViewById(R.id.txtDetail);
        imgRadar = findViewById(R.id.imgRadar);
        Button btnStartPassive = findViewById(R.id.btnStartPassive);
        Button btnStartActive = findViewById(R.id.btnStartActive);
        Button btnStop = findViewById(R.id.btnStop);

        radarAnimator = ObjectAnimator.ofFloat(imgRadar, "rotation", 0f, 360f);
        radarAnimator.setDuration(RADAR_ROTATION_MS);
        radarAnimator.setRepeatCount(ValueAnimator.INFINITE);
        radarAnimator.setInterpolator(new LinearInterpolator());

        btnStartPassive.setOnClickListener(v -> ensurePermissionAndStart(GnssSnapshot.MODE_PASSIVE));
        btnStartActive.setOnClickListener(v -> ensurePermissionAndStart(GnssSnapshot.MODE_ACTIVE));
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, GnssKeeperService.class);
            i.setAction(GnssKeeperService.ACTION_STOP);
            startService(i);
        });
    }

    private void ensurePermissionAndStart(String mode) {
        pendingMode = mode;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) {
            txtState.setText("상태: GPS 미지원 단말");
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (!isLocationEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        startKeeper(mode);
    }

    private boolean isLocationEnabled() {
        android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    private void startKeeper(String mode) {
        Intent i = new Intent(this, GnssKeeperService.class);
        i.setAction(GnssSnapshot.MODE_PASSIVE.equals(mode)
                ? GnssKeeperService.ACTION_START_PASSIVE
                : GnssKeeperService.ACTION_START_ACTIVE);
        startForegroundService(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startKeeper(pendingMode);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if (radarAnimator.isStarted()) radarAnimator.pause();
        super.onPause();
    }

    private void updateRadarAnimation() {
        if (GnssSnapshot.running) {
            if (!radarAnimator.isStarted()) {
                radarAnimator.start();
            } else if (radarAnimator.isPaused()) {
                radarAnimator.resume();
            }
        } else {
            if (radarAnimator.isStarted() && !radarAnimator.isPaused()) {
                radarAnimator.pause();
            }
        }
    }

    private void render() {
        updateRadarAnimation();

        String stateLabel;
        if (!GnssSnapshot.running) {
            stateLabel = "상태: 중지";
        } else if (GnssSnapshot.MODE_PASSIVE.equals(GnssSnapshot.mode)) {
            stateLabel = "상태: PASSIVE 관찰 중";
        } else {
            stateLabel = "상태: ACTIVE 유지 중";
        }
        txtState.setText(stateLabel);

        long ageMs = GnssSnapshot.lastLocationElapsedMs == 0L
                ? -1L : SystemClock.elapsedRealtime() - GnssSnapshot.lastLocationElapsedMs;
        String acc = Float.isNaN(GnssSnapshot.accuracyM) ? "-" : String.format(Locale.KOREA, "%.1f m", GnssSnapshot.accuracyM);
        String speed = Float.isNaN(GnssSnapshot.speedMps) ? "-" : String.format(Locale.KOREA, "%.1f km/h", GnssSnapshot.speedMps * 3.6f);
        String age = ageMs < 0 ? "-" : String.format(Locale.KOREA, "%.1f s", ageMs / 1000f);
        String gap = GnssSnapshot.lastGapMs < 0 ? "-" : String.format(Locale.KOREA, "%.1f s", GnssSnapshot.lastGapMs / 1000f);
        txtDetail.setText(
                "위치 정확도: " + acc + "\n" +
                "Fix age: " + age + "\n" +
                "이전 이벤트와 간격: " + gap + "\n" +
                "속도: " + speed + "\n" +
                "위성: " + GnssSnapshot.satellitesUsed + " / " + GnssSnapshot.satellitesVisible + " (사용/가시)\n" +
                "L1 유사 대역: " + GnssSnapshot.l1Count + "\n" +
                "L5 유사 대역: " + GnssSnapshot.l5Count + "\n" +
                "Raw measurement 이벤트: " + GnssSnapshot.measurementEvents + "\n" +
                "HW clock discontinuity: " + GnssSnapshot.clockDiscontinuity + "\n\n" +
                "로그 경로:\n" + (GnssSnapshot.logDir.isEmpty() ? "-" : GnssSnapshot.logDir));
    }
}
