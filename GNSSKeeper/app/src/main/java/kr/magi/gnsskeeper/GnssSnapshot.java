package kr.magi.gnsskeeper;

final class GnssSnapshot {
    static final String MODE_NONE = "NONE";
    static final String MODE_PASSIVE = "PASSIVE";
    static final String MODE_ACTIVE = "ACTIVE";

    static volatile boolean running = false;
    static volatile String mode = MODE_NONE;
    static volatile long lastLocationElapsedMs = 0L;
    static volatile long lastGapMs = -1L;
    static volatile float accuracyM = Float.NaN;
    static volatile float speedMps = Float.NaN;
    static volatile float bearingDeg = Float.NaN;
    static volatile int satellitesVisible = 0;
    static volatile int satellitesUsed = 0;
    static volatile int l1Count = 0;
    static volatile int l5Count = 0;
    static volatile long measurementEvents = 0L;
    static volatile int clockDiscontinuity = -1;
    static volatile String logDir = "";

    private GnssSnapshot() {}
}
