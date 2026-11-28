package kr.magi.gnsskeeper;

import java.util.ArrayList;
import java.util.List;

/** v0.3 (§18-1): persisted auto-recovery state. Pure Java; Android persistence lives in RecoveryStore. */
final class RecoveryState {
    /** Synchronous persistence. {@code commit} must report false when the write did not land. */
    interface Store {
        RecoveryState load();
        boolean commit(RecoveryState state);
    }

    static final int MAX_SEEN = 32, MAX_PENDING = 16;

    boolean runIntent, halted, sessionClosed = true;
    String lastMode = GnssSnapshot.MODE_NONE, sessionId = "";
    int pid, consecutiveFailures;
    int bootCount = -1, lastAliveBoot = -1;
    long procStartWallMs, procStartElapsedMs, lastAliveWallMs, lastAliveElapsedMs;
    /** Restart attempts: {bootCount, elapsedRealtimeMs}. */
    final List<long[]> history = new ArrayList<>();
    final List<String> exitSeen = new ArrayList<>();
    /** Events that happened with no open session ("wall|event|detail"), flushed into the next session. */
    final List<String> pending = new ArrayList<>();

    RecoveryState copy() {
        RecoveryState c = new RecoveryState();
        c.runIntent = runIntent; c.halted = halted; c.sessionClosed = sessionClosed;
        c.lastMode = lastMode; c.sessionId = sessionId; c.pid = pid; c.consecutiveFailures = consecutiveFailures;
        c.bootCount = bootCount; c.lastAliveBoot = lastAliveBoot;
        c.procStartWallMs = procStartWallMs; c.procStartElapsedMs = procStartElapsedMs;
        c.lastAliveWallMs = lastAliveWallMs; c.lastAliveElapsedMs = lastAliveElapsedMs;
        for (long[] h : history) c.history.add(new long[]{h[0], h[1]});
        c.exitSeen.addAll(exitSeen); c.pending.addAll(pending);
        return c;
    }

    void markSeen(String key) {
        if (key == null || exitSeen.contains(key)) return;
        exitSeen.add(key);
        while (exitSeen.size() > MAX_SEEN) exitSeen.remove(0);
    }

    void addPending(long wallMs, String event, String detail) {
        pending.add(wallMs + "|" + clean(event) + "|" + clean(detail));
        while (pending.size() > MAX_PENDING) pending.remove(0);
    }

    private static String clean(String s) { return s == null ? "" : s.replace('|', '/').replace(';', ','); }

    static String encodeHistory(List<long[]> h) {
        StringBuilder b = new StringBuilder();
        for (long[] x : h) { if (b.length() > 0) b.append(';'); b.append(x[0]).append(':').append(x[1]); }
        return b.toString();
    }

    static void decodeHistory(String s, List<long[]> out) {
        out.clear();
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            String[] p = part.split(":");
            if (p.length != 2) continue;
            try { out.add(new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])}); }
            catch (NumberFormatException ignored) { /* Corrupt entry: skip rather than invent a value. */ }
        }
    }

    static String encodeList(List<String> list) { return String.join(";", list); }

    static void decodeList(String s, List<String> out) {
        out.clear();
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) if (!part.isEmpty()) out.add(part);
    }
}
