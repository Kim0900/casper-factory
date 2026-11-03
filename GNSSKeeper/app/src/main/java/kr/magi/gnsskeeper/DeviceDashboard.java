package kr.magi.gnsskeeper;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.widget.TextView;
import java.util.Locale;

/** Reads public OS state only while the screen is visible. No probes, listeners or file writes. */
final class DeviceDashboard {
    static void render(Activity activity, long now) {
        int thermal = -1; Boolean saving = null;
        PowerManager power = activity.getSystemService(PowerManager.class);
        if (power != null) {
            try { thermal = power.getCurrentThermalStatus(); } catch (RuntimeException ignored) { }
            try { saving = power.isPowerSaveMode(); } catch (RuntimeException ignored) { }
        }
        String batteryTemp = "배터리 온도: 확인 불가", batteryInfo = "배터리: 확인 불가";
        try {
            Intent battery = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
                    batteryTemp = String.format(Locale.KOREA, "배터리 %.1f°C", battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                if (level >= 0 && scale > 0 && level <= scale) batteryInfo = "배터리 " + Math.round(level * 100.0 / scale) + "%";
                batteryInfo += plugged < 0 ? " · 충전 상태 미확인" : plugged > 0 ? " · 전원 연결" : " · 전원 미연결";
            }
        } catch (RuntimeException ignored) { }
        paint(activity.findViewById(R.id.cardThermal), "발열 · Android", DashboardStatus.thermal(thermal), batteryTemp);
        paint(activity.findViewById(R.id.cardPower), "절전 · Android", DashboardStatus.power(saving), batteryInfo);
        boolean known = false, connected = false, internet = false, validated = false;
        try {
            ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                // A non-null network with unavailable capabilities may be in transition: not a confirmed outage.
                known = network == null || caps != null; connected = network != null;
                if (caps != null) {
                    internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
            }
        } catch (RuntimeException ignored) { }
        paint(activity.findViewById(R.id.cardNetwork), "인터넷 · OS 판정", DashboardStatus.network(known, connected, internet, validated), "카카오 서버 접속 판정 아님");
        Boolean locationEnabled = null;
        try {
            LocationManager lm = activity.getSystemService(LocationManager.class);
            if (lm != null) locationEnabled = lm.isLocationEnabled();
        } catch (RuntimeException ignored) { }
        boolean fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        paint(activity.findViewById(R.id.cardPermission), "위치 권한 · 이 앱", DashboardStatus.permission(fine, coarse, locationEnabled), "카카오 앱 권한은 별도");
        boolean passive = GnssSnapshot.MODE_PASSIVE.equals(GnssSnapshot.mode);
        String source = GnssSnapshot.sourceProvider.isEmpty() ? "공급자: 아직 없음" : "공급자: " + GnssSnapshot.sourceProvider;
        paint(activity.findViewById(R.id.cardLocation), "위치 · 공통 PASSIVE 관찰", DashboardStatus.location(GnssSnapshot.running, passive, GnssSnapshot.lastFixElapsedRealtimeNanos, now), source);
        paint(activity.findViewById(R.id.cardService), "Keeper 실행", DashboardStatus.service(GnssSnapshot.phase), GnssSnapshot.mode + " · 위치 수신과 별도");
    }
    static void paint(TextView view, String title, DashboardStatus status, String extra) {
        String text = title + "\n" + status.label + "\n" + extra;
        view.setText(text); view.setTextColor(status.band.color);
        view.setContentDescription(title + ": " + status.label + ", " + status.band.label + ", " + extra);
        if (!Integer.valueOf(status.band.color).equals(view.getTag())) {
            float density = view.getResources().getDisplayMetrics().density;
            GradientDrawable card = new GradientDrawable(); card.setColor(0xff1b1e25);
            card.setCornerRadius(12 * density); card.setStroke(Math.max(1, Math.round(2 * density)), status.band.color);
            view.setBackground(card); view.setTag(status.band.color);
        }
    }
}
