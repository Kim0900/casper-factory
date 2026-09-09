package kr.magi.gnsskeeper;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CsvLogger {
    private BufferedWriter locationWriter;
    private BufferedWriter statusWriter;
    private BufferedWriter measurementWriter;
    final File sessionDir;

    CsvLogger(Context context) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(new Date());
        File base = context.getExternalFilesDir("gnss_logs");
        if (base == null) base = context.getFilesDir();
        sessionDir = new File(base, stamp);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("Cannot create log directory: " + sessionDir);
        }
        locationWriter = writer("location.csv",
                "wall_time_ms,elapsed_realtime_nanos,latitude,longitude,accuracy_m,speed_mps,bearing_deg,altitude_m,provider\n");
        statusWriter = writer("gnss_status.csv",
                "wall_time_ms,visible,used_in_fix,l1_like,l5_like,max_cn0_dbhz,avg_cn0_dbhz\n");
        measurementWriter = writer("gnss_measurements.csv",
                "wall_time_ms,event_index,clock_discontinuity,measurement_count,l1_like,l5_like,avg_cn0_dbhz\n");
    }

    private BufferedWriter writer(String name, String header) throws IOException {
        BufferedWriter w = new BufferedWriter(new FileWriter(new File(sessionDir, name), true));
        w.write(header);
        w.flush();
        return w;
    }

    synchronized void logLocation(long wall, long elapsedNanos, double lat, double lon,
                                  float accuracy, float speed, float bearing, double altitude, String provider) {
        try {
            locationWriter.write(String.format(Locale.US,
                    "%d,%d,%.8f,%.8f,%.2f,%.3f,%.2f,%.2f,%s\n",
                    wall, elapsedNanos, lat, lon, accuracy, speed, bearing, altitude, safe(provider)));
            locationWriter.flush();
        } catch (IOException ignored) {}
    }

    synchronized void logStatus(long wall, int visible, int used, int l1, int l5, float maxCn0, float avgCn0) {
        try {
            statusWriter.write(String.format(Locale.US,
                    "%d,%d,%d,%d,%d,%.2f,%.2f\n", wall, visible, used, l1, l5, maxCn0, avgCn0));
            statusWriter.flush();
        } catch (IOException ignored) {}
    }

    synchronized void logMeasurement(long wall, long eventIdx, int discontinuity, int count,
                                     int l1, int l5, float avgCn0) {
        try {
            measurementWriter.write(String.format(Locale.US,
                    "%d,%d,%d,%d,%d,%d,%.2f\n",
                    wall, eventIdx, discontinuity, count, l1, l5, avgCn0));
            measurementWriter.flush();
        } catch (IOException ignored) {}
    }

    synchronized void close() {
        try { if (locationWriter != null) locationWriter.close(); } catch (IOException ignored) {}
        try { if (statusWriter != null) statusWriter.close(); } catch (IOException ignored) {}
        try { if (measurementWriter != null) measurementWriter.close(); } catch (IOException ignored) {}
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(',', '_').replace('\n', '_');
    }
}
