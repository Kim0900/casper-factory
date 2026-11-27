package kr.magi.gnsskeeper;

/** Immutable observer data. Android callbacks and host fixtures use the same record path. */
final class LocationSample {
    final long wallMs, fixNanos, receivedNanos, sequence;
    final double latitude, longitude, altitude;
    final float accuracy, speed, bearing;
    final String sourceProvider;
    final boolean mock;
    LocationSample(long wallMs, long fixNanos, long receivedNanos, long sequence,
            double latitude, double longitude, double altitude, float accuracy,
            float speed, float bearing, String sourceProvider, boolean mock) {
        this.wallMs = wallMs; this.fixNanos = fixNanos; this.receivedNanos = receivedNanos;
        this.sequence = sequence; this.latitude = latitude; this.longitude = longitude;
        this.altitude = altitude; this.accuracy = accuracy; this.speed = speed;
        this.bearing = bearing; this.sourceProvider = sourceProvider == null ? "unknown" : sourceProvider;
        this.mock = mock;
    }
    long ageMs() { return Math.max(0L, (receivedNanos - fixNanos) / 1_000_000L); }
    boolean within(long startNanos) { return fixNanos >= startNanos && fixNanos <= receivedNanos; }
}
