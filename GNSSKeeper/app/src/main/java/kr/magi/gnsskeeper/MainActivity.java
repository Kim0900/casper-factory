package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Locale;

/**
 * v0.2.3(2026-09-10) — 레이더 회전을 ObjectAnimator 대신 Handler로 직접
 * 구동하도록 재작성. v0.2.1/0.2.2에서 ObjectAnimator(isStarted/isPaused
 * 상태분기)가 실기기에서 회전을 보여주지 못하는 문제가 재현되어,
 * 원인을 완전히 특정하기보다 훨씬 단순하고 실패 여지가 적은 방식으로
 * 교체함 — 별도 60ms 틱마다 각도를 직접 계산해 imgRadar.setRotation()을
 * 매번 명시적으로 호출한다. GnssSnapshot.running이 true일 때만 각도가
 * 증가하고, false면 마지막 각도에서 그대로 멈춘다.
 */
public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private static final long RADAR_TICK_MS = 60L;
    private static final float RADAR_DEGREES_PER_TICK = 4.5f; // 60ms*80틱=4.8s/바퀴

    private TextView txtState;
    private TextView txtDetail;
    private ImageView imgRadar;
    private float radarAngle = 0f;
    private String pendingMode = GnssSnapshot.MODE_ACTIVE;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable radarTick = new Runnable() {
        @Override public void run() {
            if (GnssSnapshot.running) {
                radarAngle = (radarAngle + RADAR_DEGREES_PER_TICK) % 360f;
                imgRadar.setRotation(radarAngle);
            }
            handler.postDelayed(this, RADAR_TICK_MS);
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
        handler.post(radarTick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(radarTick);
        super.onPause();
    }

    private void render() {
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
