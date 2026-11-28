package kr.magi.gnsskeeper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * v0.3 (§18-7): the ONLY source of location/GNSS request parameters. Manual start and automatic
 * restart both build their requests from {@link #forStart}; the start cause cannot change them.
 * Values are identical to v0.2.8/v0.2.9 and pinned by RequestSpecTest.
 */
final class RequestSpec {
    final String kind;
    final long intervalMs, minIntervalMs;
    final float minDistanceM;
    final boolean highAccuracy, fullTracking;

    private RequestSpec(String kind, long intervalMs, long minIntervalMs, float minDistanceM,
            boolean highAccuracy, boolean fullTracking) {
        this.kind = kind; this.intervalMs = intervalMs; this.minIntervalMs = minIntervalMs;
        this.minDistanceM = minDistanceM; this.highAccuracy = highAccuracy; this.fullTracking = fullTracking;
    }

    static final RequestSpec PASSIVE_OBSERVER = new RequestSpec("passive", 1000L, 0L, 0f, false, false);
    static final RequestSpec ACTIVE_GPS = new RequestSpec("gps", 1000L, 500L, 0f, true, false);
    static final RequestSpec GNSS_STATUS = new RequestSpec("gnss_status", 0L, 0L, 0f, false, false);
    static final RequestSpec GNSS_MEASUREMENTS = new RequestSpec("gnss_measurements", 0L, 0L, 0f, false, true);

    /** @param cause MANUAL / AUTO_RESTART / NOTIFICATION — intentionally ignored. */
    static List<RequestSpec> forStart(String mode, String cause) {
        List<RequestSpec> out = new ArrayList<>();
        out.add(PASSIVE_OBSERVER);
        if (GnssSnapshot.MODE_ACTIVE.equals(mode)) {
            out.add(ACTIVE_GPS); out.add(GNSS_STATUS); out.add(GNSS_MEASUREMENTS);
        }
        return Collections.unmodifiableList(out);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof RequestSpec)) return false;
        RequestSpec r = (RequestSpec) o;
        return kind.equals(r.kind) && intervalMs == r.intervalMs && minIntervalMs == r.minIntervalMs
                && Float.compare(minDistanceM, r.minDistanceM) == 0 && highAccuracy == r.highAccuracy
                && fullTracking == r.fullTracking;
    }
    @Override public int hashCode() { return Objects.hash(kind, intervalMs, minIntervalMs, minDistanceM, highAccuracy, fullTracking); }
    @Override public String toString() {
        return kind + "{interval=" + intervalMs + ",minInterval=" + minIntervalMs + ",minDistance=" + minDistanceM
                + ",highAccuracy=" + highAccuracy + ",fullTracking=" + fullTracking + "}";
    }
}
