package kr.magi.mirror.common;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/** Only operational counters. Images, addresses, fingerprints and exception strings are never stored. */
public final class Diagnostics {
    private final long started = SystemClock.elapsedRealtime();
    private final List<String> rows = new ArrayList<>();
    private long lastSample = -60000;
    public volatile long frames, connections, resizes, decoderCreations, encoderCreations;

    public synchronized void event(String event) {
        if (rows.size() < 300) rows.add((SystemClock.elapsedRealtime()-started) + ",event," + event);
    }

    public synchronized boolean sample(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        int thermal = Build.VERSION.SDK_INT >= 29 ? power.getCurrentThermalStatus() : -1;
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (elapsed - lastSample >= 60000) {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            BatteryManager manager = context.getSystemService(BatteryManager.class);
            int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int temperature = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int plugged = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            long charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (rows.size() < 300) rows.add(elapsed+",sample,"+level+","+scale+","+temperature+","+plugged+","+charge+","+thermal);
            lastSample = elapsed;
        }
        return thermal >= 3;
    }

    private String joinedRows() {
        StringBuilder text=new StringBuilder();
        for(String row:rows) text.append(row).append("\n");
        return text.toString();
    }

    public synchronized String export(String role) {
        return "GEUMBOK_SYNTHETIC_ONLY_V1\nrole="+role+"\nframes="+frames+"\nconnections="+connections
                +"\nresizes="+resizes+"\nencoder_creations="+encoderCreations+"\ndecoder_creations="+decoderCreations
                +"\nsample_fields=elapsed_ms,type,level,scale,temperature_tenths_C,plugged,charge_uAh,thermal\n"
                +joinedRows()+"\n";
    }
}
