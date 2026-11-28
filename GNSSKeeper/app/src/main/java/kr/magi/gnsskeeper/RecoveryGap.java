package kr.magi.gnsskeeper;

import java.util.Locale;

/**
 * v0.3 (§18-4): off→on gap. Same boot → monotonic (elapsedRealtime) difference, a wall-clock
 * disagreement > 2 s is a clock correction (not a reboot). Different boot → wall clock, ESTIMATED.
 * "on" is the first valid fix, never the first log line.
 */
final class RecoveryGap {
    static final long CLOCK_TOLERANCE_MS = 2_000L;

    final long offWallMs, onWallMs, gapMs;
    final boolean exact, monotonic, reboot, clockAdjusted;

    private RecoveryGap(long offWallMs, long onWallMs, long gapMs, boolean exact, boolean monotonic,
            boolean reboot, boolean clockAdjusted) {
        this.offWallMs = offWallMs; this.onWallMs = onWallMs; this.gapMs = Math.max(0L, gapMs);
        this.exact = exact; this.monotonic = monotonic; this.reboot = reboot; this.clockAdjusted = clockAdjusted;
    }

    /**
     * @param exitWallMs ApplicationExitInfo timestamp of the matched exit, or a value <= 0 when unknown.
     */
    static RecoveryGap compute(int prevBoot, long prevAliveWallMs, long prevAliveElapsedMs, long exitWallMs,
            int nowBoot, long nowWallMs, long nowElapsedMs) {
        boolean sameBoot = prevBoot >= 0 && prevBoot == nowBoot && nowElapsedMs >= prevAliveElapsedMs;
        if (sameBoot) {
            long mono = nowElapsedMs - prevAliveElapsedMs;
            long wall = nowWallMs - prevAliveWallMs;
            boolean adjusted = Math.abs(wall - mono) > CLOCK_TOLERANCE_MS;
            // Exit timestamp may be used only while wall and monotonic clocks agree; it trims the
            // up-to-10 s heartbeat lag. Never move "off" before the last heartbeat.
            boolean useExit = !adjusted && exitWallMs > 0 && exitWallMs >= prevAliveWallMs && exitWallMs <= nowWallMs;
            long lag = useExit ? exitWallMs - prevAliveWallMs : 0L;
            long off = useExit ? exitWallMs : prevAliveWallMs;
            return new RecoveryGap(off, nowWallMs, mono - lag, useExit, true, false, adjusted);
        }
        boolean useExit = exitWallMs > 0 && exitWallMs >= prevAliveWallMs && exitWallMs <= nowWallMs;
        long off = useExit ? exitWallMs : prevAliveWallMs;
        return new RecoveryGap(off, nowWallMs, nowWallMs - off, false, false, true, false);
    }

    String detail(String cause, boolean censored) {
        return String.format(Locale.ROOT,
                "off=%d;on=%d;gap_s=%.1f;bound=%s;off_source=%s;clock=%s;reboot=%d;clock_adjusted=%d;censored=%d;cause=%s",
                offWallMs, onWallMs, gapMs / 1000.0, exact ? "EXACT" : "ESTIMATED", exact ? "EXIT_INFO" : "HEARTBEAT",
                monotonic ? "MONO" : "WALL", reboot ? 1 : 0, clockAdjusted ? 1 : 0, censored ? 1 : 0, cause);
    }
}
