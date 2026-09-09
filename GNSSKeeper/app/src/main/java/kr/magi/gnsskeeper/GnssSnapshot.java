package kr.magi.gnsskeeper;

final class GnssSnapshot {
    static volatile boolean running = false;
    static volatile long lastLocationElapsedMs = 0L;
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
