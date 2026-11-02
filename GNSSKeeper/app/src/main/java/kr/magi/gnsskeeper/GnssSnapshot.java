package kr.magi.gnsskeeper;

/** Screen/notification state: registrations and actually received data are separate. */
final class GnssSnapshot {
    static final String MODE_NONE = "NONE", MODE_PASSIVE = "PASSIVE", MODE_ACTIVE = "ACTIVE";
    static final long STALE_AFTER_NANOS = 5_000_000_000L;
    static volatile String phase = "STOPPED", mode = MODE_NONE, lastError = "", logDir = "", sourceProvider = "";
    static volatile boolean running, observerRegistered, statusRegistered, measurementsRegistered;
    static volatile long lastFixElapsedRealtimeNanos, lastObserverNanos, lastStatusNanos, lastMeasurementNanos;
    static volatile long lastGapMs = -1, measurementEvents, observerSamples, droppedCallbacks;
    static volatile float accuracyM = Float.NaN, speedMps = Float.NaN;
    static volatile int satellitesVisible, satellitesUsed, l1Count, l5Count, clockDiscontinuity = -1;
    static boolean fresh(long time, long now) { return time > 0 && now >= time && now - time <= STALE_AFTER_NANOS; }
    static void resetMeasurements() {
        lastFixElapsedRealtimeNanos = lastObserverNanos = lastStatusNanos = lastMeasurementNanos = 0;
        lastGapMs = -1; measurementEvents = observerSamples = droppedCallbacks = 0;
        accuracyM = speedMps = Float.NaN; sourceProvider = "";
        satellitesVisible = satellitesUsed = l1Count = l5Count = 0; clockDiscontinuity = -1;
        statusRegistered = measurementsRegistered = false;
    }
}
