package kr.magi.gnsskeeper;

/**
 * v0.3 (§18-0): 20 MB per-session HARD cap over the whole session folder, metadata included.
 * The last 64 KB is reserved for terminal/recovery events. Summary rows stop at cap − reserve;
 * nothing is ever written past the hard cap.
 */
final class LogBudget {
    static final long HARD_CAP_BYTES = 20L * 1024L * 1024L;
    static final long TERMINAL_RESERVE_BYTES = 64L * 1024L;

    private final long hardCap, reserve;
    private long used, metadata;
    private boolean capped;

    LogBudget() { this(HARD_CAP_BYTES, TERMINAL_RESERVE_BYTES); }
    LogBudget(long hardCap, long reserve) {
        if (reserve < 0 || reserve >= hardCap) throw new IllegalArgumentException("reserve");
        this.hardCap = hardCap; this.reserve = reserve;
    }

    /** Ordinary rows. Returns false (and latches capped) once the summary area is exhausted. */
    synchronized boolean trySummary(long bytes) {
        if (capped) return false;
        if (used + metadata + bytes > hardCap - reserve) { capped = true; return false; }
        used += bytes;
        return true;
    }

    /** Terminal/recovery events may use the reserve but never exceed the hard cap. */
    synchronized boolean tryTerminal(long bytes) {
        if (used + metadata + bytes > hardCap) return false;
        used += bytes;
        return true;
    }

    /** session.json is rewritten in place: account its current size, not the sum of rewrites. */
    synchronized boolean setMetadata(long bytes) {
        if (used + bytes > hardCap) return false;
        metadata = bytes;
        return true;
    }

    synchronized boolean capped() { return capped; }
    synchronized long total() { return used + metadata; }
}
