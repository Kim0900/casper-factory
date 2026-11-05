package kr.magi.mirror.common;

/** One socket outage. Terminal events are deliberately different from a retryable EOF. */
public final class RetryBudget {
    public enum Failure { SOCKET, NETWORK_CHANGED, LOCKED, KEY_MISMATCH, PERMISSION_REVOKED, STOPPED, PROTOCOL }
    private static final long[] DELAYS = {250, 500, 1000};
    private final long started;
    private int attempts;

    public RetryBudget(long nowMillis) { started = nowMillis; }

    public long nextDelay(Failure failure, long nowMillis) {
        if (failure != Failure.SOCKET || attempts == DELAYS.length) return -1;
        long delay = DELAYS[attempts];
        if (nowMillis - started + delay >= 15000) return -1;
        attempts++;
        return delay;
    }

    public int connectTimeout(long nowMillis) {
        return (int) Math.max(0, Math.min(3500, 15000 - (nowMillis - started)));
    }
}
