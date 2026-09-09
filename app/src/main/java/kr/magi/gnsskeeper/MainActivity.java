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
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private TextView txtState;
    private TextView txtDetail;
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
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> ensurePermissionAndStart());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, GnssKeeperService.class);
            i.setAction(GnssKeeperService.ACTION_STOP);
            startService(i);
        });
    }

    private void ensurePermissionAndStart() {
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
        startKeeper();
    }

    private boolean isLocationEnabled() {
        android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    private void startKeeper() {
        Intent i = new Intent(this, GnssKeeperService.class);
        i.setAction(GnssKeeperService.ACTION_START);
        startForegroundService(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startKeeper();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        txtState.setText(GnssSnapshot.running ? "상태: 실행 중" : "상태: 중지");
        long ageMs = GnssSnapshot.lastLocationElapsedMs == 0L
                ? -1L : SystemClock.elapsedRealtime() - GnssSnapshot.lastLocationElapsedMs;
        String acc = Float.isNaN(GnssSnapshot.accuracyM) ? "-" : String.format(Locale.KOREA, "%.1f m", GnssSnapshot.accuracyM);
        String speed = Float.isNaN(GnssSnapshot.speedMps) ? "-" : String.format(Locale.KOREA, "%.1f km/h", GnssSnapshot.speedMps * 3.6f);
        String age = ageMs < 0 ? "-" : String.format(Locale.KOREA, "%.1f s", ageMs / 1000f);
        txtDetail.setText(
                "위치 정확도: " + acc + "\n" +
                "Fix age: " + age + "\n" +
                "속도: " + speed + "\n" +
                "위성: " + GnssSnapshot.satellitesUsed + " / " + GnssSnapshot.satellitesVisible + " (사용/가시)\n" +
                "L1 유사 대역: " + GnssSnapshot.l1Count + "\n" +
                "L5 유사 대역: " + GnssSnapshot.l5Count + "\n" +
                "Raw measurement 이벤트: " + GnssSnapshot.measurementEvents + "\n" +
                "HW clock discontinuity: " + GnssSnapshot.clockDiscontinuity + "\n\n" +
                "로그 경로:\n" + (GnssSnapshot.logDir.isEmpty() ? "-" : GnssSnapshot.logDir));
    }
}
