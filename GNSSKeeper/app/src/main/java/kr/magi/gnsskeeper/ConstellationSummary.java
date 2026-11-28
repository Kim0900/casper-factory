package kr.magi.gnsskeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * v0.3 (§3-2, §8): per constellation × band summary of the latest GnssStatus, at most once per 5 s.
 * Observation only — no request change. Band split follows the existing L1-like/L5-like ranges.
 */
final class ConstellationSummary {
    static final long INTERVAL_NANOS = 5_000_000_000L;
    static final String HEADER = "wall_time_ms,received_elapsed_nanos,constellation,band,visible,used,avg_cn0_dbhz,max_cn0_dbhz,avg_baseband_cn0_dbhz";
    private long lastFlushNanos = Long.MIN_VALUE;

    static String constellation(int type) {
        // android.location.GnssStatus CONSTELLATION_* values (pinned by AndroidConstantsTest).
        switch (type) {
            case 1: return "GPS";
            case 2: return "SBAS";
            case 3: return "GLONASS";
            case 4: return "QZSS";
            case 5: return "BEIDOU";
            case 6: return "GALILEO";
            case 7: return "IRNSS";
            default: return "UNKNOWN";
        }
    }

    static String band(float carrierHz) {
        if (Float.isNaN(carrierHz) || carrierHz <= 0) return "NA";
        float mhz = carrierHz / 1_000_000f;
        if (mhz >= 1550 && mhz <= 1610) return "L1";
        if (mhz >= 1150 && mhz <= 1250) return "L5";
        return "OTHER";
    }

    /** True when a summary is due (first call, or ≥ 5 s since the last flush). */
    boolean due(long nowNanos) {
        if (lastFlushNanos != Long.MIN_VALUE && nowNanos - lastFlushNanos < INTERVAL_NANOS) return false;
        lastFlushNanos = nowNanos;
        return true;
    }

    /**
     * @param carrierHz NaN when unavailable; @param basebandCn0 NaN when unavailable.
     * @return CSV rows (without the wall/receipt prefix): constellation,band,visible,used,avg,max,avgBaseband
     */
    static List<String> rows(int[] constellation, float[] carrierHz, boolean[] used, float[] cn0, float[] basebandCn0) {
        Map<String, double[]> agg = new TreeMap<>(); // visible, used, sumCn0, maxCn0, sumBb, nBb
        for (int i = 0; i < constellation.length; i++) {
            String key = constellation(constellation[i]) + "," + band(carrierHz[i]);
            double[] a = agg.computeIfAbsent(key, k -> new double[]{0, 0, 0, 0, 0, 0});
            a[0]++; if (used[i]) a[1]++;
            a[2] += cn0[i]; a[3] = Math.max(a[3], cn0[i]);
            if (!Float.isNaN(basebandCn0[i])) { a[4] += basebandCn0[i]; a[5]++; }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : agg.entrySet()) {
            double[] a = e.getValue();
            out.add(String.format(Locale.ROOT, "%s,%d,%d,%.1f,%.1f,%s", e.getKey(), (long) a[0], (long) a[1],
                    a[2] / a[0], a[3], a[5] == 0 ? "NaN" : String.format(Locale.ROOT, "%.1f", a[4] / a[5])));
        }
        return out;
    }
}
