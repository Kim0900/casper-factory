package kr.magi.gnsskeeper;

import java.util.List;

/**
 * v0.3 (§18-1/§18-4): classifies ApplicationExitInfo reasons and matches them to the previous
 * process. Pure Java; numeric values mirror android.app.ApplicationExitInfo (pinned by a unit test).
 */
final class ExitReasons {
    static final int UNKNOWN = 0, EXIT_SELF = 1, SIGNALED = 2, LOW_MEMORY = 3, CRASH = 4, CRASH_NATIVE = 5,
            ANR = 6, INITIALIZATION_FAILURE = 7, PERMISSION_CHANGE = 8, EXCESSIVE_RESOURCE_USAGE = 9,
            USER_REQUESTED = 10, USER_STOPPED = 11, DEPENDENCY_DIED = 12, OTHER = 13, FREEZER = 14,
            PACKAGE_STATE_CHANGE = 15, PACKAGE_UPDATED = 16;

    /** USER_STOP: intentional (Task Manager, force stop, permission/package change) — never restored, excluded from gap. */
    enum Kind { USER_STOP, OS_KILL, APP_CRASH, UNKNOWN }

    private ExitReasons() { }

    static Kind classify(int reason) {
        switch (reason) {
            case USER_REQUESTED: case USER_STOPPED: case PERMISSION_CHANGE:
            case PACKAGE_STATE_CHANGE: case PACKAGE_UPDATED:
                return Kind.USER_STOP;
            case CRASH: case CRASH_NATIVE: case ANR: case INITIALIZATION_FAILURE:
                return Kind.APP_CRASH;
            case SIGNALED: case LOW_MEMORY: case EXCESSIVE_RESOURCE_USAGE: case DEPENDENCY_DIED:
            case OTHER: case FREEZER:
                return Kind.OS_KILL;
            default:
                return Kind.UNKNOWN;
        }
    }

    static String name(int reason) {
        switch (reason) {
            case EXIT_SELF: return "REASON_EXIT_SELF";
            case SIGNALED: return "REASON_SIGNALED";
            case LOW_MEMORY: return "REASON_LOW_MEMORY";
            case CRASH: return "REASON_CRASH";
            case CRASH_NATIVE: return "REASON_CRASH_NATIVE";
            case ANR: return "REASON_ANR";
            case INITIALIZATION_FAILURE: return "REASON_INITIALIZATION_FAILURE";
            case PERMISSION_CHANGE: return "REASON_PERMISSION_CHANGE";
            case EXCESSIVE_RESOURCE_USAGE: return "REASON_EXCESSIVE_RESOURCE_USAGE";
            case USER_REQUESTED: return "REASON_USER_REQUESTED";
            case USER_STOPPED: return "REASON_USER_STOPPED";
            case DEPENDENCY_DIED: return "REASON_DEPENDENCY_DIED";
            case OTHER: return "REASON_OTHER";
            case FREEZER: return "REASON_FREEZER";
            case PACKAGE_STATE_CHANGE: return "REASON_PACKAGE_STATE_CHANGE";
            case PACKAGE_UPDATED: return "REASON_PACKAGE_UPDATED";
            default: return "REASON_UNKNOWN";
        }
    }

    static final class Record {
        final int pid, reason;
        final long timestampMs;
        final String description;
        Record(int pid, long timestampMs, int reason, String description) {
            this.pid = pid; this.timestampMs = timestampMs; this.reason = reason;
            this.description = description == null ? "" : description;
        }
        String key() { return pid + "@" + timestampMs; }
        Kind kind() { return classify(reason); }
    }

    /**
     * The newest record of the previous process: same pid, timestamp within [procStartWall, now].
     * Records already processed (key in {@code seen}) are ignored so one exit is never counted twice.
     */
    static Record match(List<Record> records, int pid, long procStartWallMs, long nowWallMs, List<String> seen) {
        if (records == null || pid <= 0) return null;
        Record best = null;
        for (Record r : records) {
            if (r == null || r.pid != pid) continue;
            if (r.timestampMs < procStartWallMs || r.timestampMs > nowWallMs) continue;
            if (seen != null && seen.contains(r.key())) continue;
            if (best == null || r.timestampMs > best.timestampMs) best = r;
        }
        return best;
    }
}
