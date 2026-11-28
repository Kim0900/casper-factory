package kr.magi.gnsskeeper;

import android.content.Context;
import android.content.SharedPreferences;

/** v0.3 (§18-1): app-private SharedPreferences, synchronous commit() whose result is honoured. */
final class RecoveryStore implements RecoveryState.Store {
    static final String PREFS = "gnss_keeper_recovery";
    private final SharedPreferences prefs;

    RecoveryStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @Override public RecoveryState load() {
        RecoveryState s = new RecoveryState();
        s.runIntent = prefs.getBoolean("run_intent", false);
        s.halted = prefs.getBoolean("auto_restart_halted", false);
        s.sessionClosed = prefs.getBoolean("session_closed", true);
        s.lastMode = prefs.getString("last_mode", GnssSnapshot.MODE_NONE);
        s.sessionId = prefs.getString("session_id", "");
        s.pid = prefs.getInt("pid", 0);
        s.consecutiveFailures = prefs.getInt("consecutive_start_failures", 0);
        s.bootCount = prefs.getInt("boot_count", -1);
        s.lastAliveBoot = prefs.getInt("last_alive_boot_count", -1);
        s.procStartWallMs = prefs.getLong("proc_start_wall_ms", 0L);
        s.procStartElapsedMs = prefs.getLong("proc_start_elapsed_ms", 0L);
        s.lastAliveWallMs = prefs.getLong("last_alive_wall_ms", 0L);
        s.lastAliveElapsedMs = prefs.getLong("last_alive_elapsed_ms", 0L);
        RecoveryState.decodeHistory(prefs.getString("restart_history", ""), s.history);
        RecoveryState.decodeList(prefs.getString("exit_seen", ""), s.exitSeen);
        String pending = prefs.getString("pending_events", "");
        s.pending.clear();
        if (pending != null && !pending.isEmpty()) for (String p : pending.split("\n")) if (!p.isEmpty()) s.pending.add(p);
        return s;
    }

    @Override public boolean commit(RecoveryState s) {
        return prefs.edit()
                .putBoolean("run_intent", s.runIntent)
                .putBoolean("auto_restart_halted", s.halted)
                .putBoolean("session_closed", s.sessionClosed)
                .putString("last_mode", s.lastMode)
                .putString("session_id", s.sessionId)
                .putInt("pid", s.pid)
                .putInt("consecutive_start_failures", s.consecutiveFailures)
                .putInt("boot_count", s.bootCount)
                .putInt("last_alive_boot_count", s.lastAliveBoot)
                .putLong("proc_start_wall_ms", s.procStartWallMs)
                .putLong("proc_start_elapsed_ms", s.procStartElapsedMs)
                .putLong("last_alive_wall_ms", s.lastAliveWallMs)
                .putLong("last_alive_elapsed_ms", s.lastAliveElapsedMs)
                .putString("restart_history", RecoveryState.encodeHistory(s.history))
                .putString("exit_seen", RecoveryState.encodeList(s.exitSeen))
                .putString("pending_events", String.join("\n", s.pending))
                .commit();
    }
}
