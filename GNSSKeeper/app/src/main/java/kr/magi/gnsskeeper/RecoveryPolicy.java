package kr.magi.gnsskeeper;

import java.util.Iterator;

/**
 * v0.3 (§18-6, authoritative constants §18-0): restore order for a system STICKY re-creation.
 * halted → run_intent → prior exit reason → same-boot monotonic loop window → persist attempt
 * (fail closed) → only then may the caller touch foreground service / location.
 */
final class RecoveryPolicy {
    static final long LOOP_WINDOW_MS = 10L * 60L * 1000L;
    static final int MAX_RESTARTS_IN_WINDOW = 3;
    static final int MAX_CONSECUTIVE_START_FAILURES = 2;
    /** A restart counts as a success only if the first valid fix arrived and the session survived this long. */
    static final long SUCCESS_AFTER_MS = 60_000L;

    enum Outcome { RESTORE, SKIP_HALTED, SKIP_NOT_RUNNING, SKIP_USER_STOP, HALT_LOOP, HALT_COMMIT_FAIL }

    private RecoveryPolicy() { }

    static Outcome onSystemRestart(RecoveryState.Store store, RecoveryState s, ExitReasons.Kind priorExit,
            int bootCount, long elapsedMs) {
        if (s.halted) return Outcome.SKIP_HALTED;                       // 1. halted first, before anything else
        if (!s.runIntent) return Outcome.SKIP_NOT_RUNNING;               // 2. user switched it off in the app
        if (priorExit == ExitReasons.Kind.USER_STOP) {                   // 3. Task Manager / force stop
            s.runIntent = false;
            store.commit(s);                                             // best effort: not restoring either way
            return Outcome.SKIP_USER_STOP;
        }
        int recent = 0;                                                  // 4. same boot, monotonic window only
        for (Iterator<long[]> it = s.history.iterator(); it.hasNext(); ) {
            long[] h = it.next();
            boolean sameBoot = h[0] == bootCount && bootCount >= 0;
            boolean inWindow = sameBoot && elapsedMs >= h[1] && elapsedMs - h[1] <= LOOP_WINDOW_MS;
            if (inWindow) recent++; else it.remove();
        }
        if (recent >= MAX_RESTARTS_IN_WINDOW || s.consecutiveFailures >= MAX_CONSECUTIVE_START_FAILURES) {
            s.halted = true;
            store.commit(s);
            return Outcome.HALT_LOOP;
        }
        s.history.add(new long[]{bootCount, elapsedMs});                 // 5. persist the attempt BEFORE risky work
        s.consecutiveFailures++;                                         //    undone only by onRestoreSucceeded
        if (!store.commit(s)) return Outcome.HALT_COMMIT_FAIL;           //    fail closed
        return Outcome.RESTORE;
    }

    /** First valid fix arrived and the session lived SUCCESS_AFTER_MS. */
    static boolean onRestoreSucceeded(RecoveryState.Store store, RecoveryState s) {
        s.consecutiveFailures = 0;
        return store.commit(s);
    }

    /** Explicit start from the app screen: the ONLY way to clear a halt. */
    static boolean onManualStart(RecoveryState.Store store, RecoveryState s, String mode) {
        s.runIntent = true; s.lastMode = mode; s.halted = false;
        s.consecutiveFailures = 0; s.history.clear();
        return store.commit(s);
    }

    static boolean onUserStop(RecoveryState.Store store, RecoveryState s) {
        s.runIntent = false; s.sessionClosed = true;
        return store.commit(s);
    }
}
