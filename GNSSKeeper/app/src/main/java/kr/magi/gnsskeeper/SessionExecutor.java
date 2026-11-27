package kr.magi.gnsskeeper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lifecycle commands never wait for storage. A bounded callback backlog fails explicitly. */
final class SessionExecutor {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GNSSKeeper-records"); t.setDaemon(true); return t;
    });
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean retired = new AtomicBoolean();
    private final int capacity;
    SessionExecutor() { this(256); }
    SessionExecutor(int capacity) { this.capacity = capacity; }
    long nextEpoch() { return epoch.incrementAndGet(); }
    long epoch() { return epoch.get(); }
    boolean current(long e) { return !retired.get() && epoch.get() == e; }
    boolean control(long e, Runnable command) {
        try { worker.execute(() -> { if (current(e)) command.run(); }); return true; }
        catch (RejectedExecutionException ex) { return false; }
    }
    boolean callback(long e, Runnable command) {
        if (!current(e)) return true;
        if (pending.incrementAndGet() > capacity) { pending.decrementAndGet(); return false; }
        try {
            worker.execute(() -> {
                try { if (current(e)) command.run(); }
                finally { pending.decrementAndGet(); }
            });
            return true;
        } catch (RejectedExecutionException ex) { pending.decrementAndGet(); return false; }
    }
    void retire(Runnable cleanup) {
        if (!retired.compareAndSet(false, true)) return;
        epoch.incrementAndGet();
        worker.execute(cleanup);
        worker.shutdown(); // No join/awaitTermination on Android's main thread.
    }
}
