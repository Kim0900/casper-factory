package kr.magi.mirror.common;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Terminal route ownership: invalidate closes sockets before notifying the UI. */
public final class RouteLease implements AutoCloseable {
    private boolean open = true;
    private Runnable invalidated;
    private final List<Closeable> resources = new ArrayList<>();

    public synchronized boolean valid() { return open; }
    public synchronized <T extends Closeable> T own(T resource) throws IOException {
        if (!open) { resource.close(); throw new IOException("Route ended"); }
        resources.removeIf(item -> (item instanceof java.net.Socket && ((java.net.Socket)item).isClosed())
                || (item instanceof java.net.ServerSocket && ((java.net.ServerSocket)item).isClosed()));
        resources.add(resource); return resource;
    }
    public void watch(Runnable action) {
        boolean ended;
        synchronized (this) {
            if (invalidated != null) throw new IllegalStateException("Already watching route");
            invalidated = action; ended = !open;
        }
        if (ended) action.run();
    }
    public void invalidate() { finish(true); }
    @Override public void close() { finish(false); }
    private void finish(boolean notify) {
        List<Closeable> closing; Runnable action;
        synchronized (this) {
            if (!open) return;
            open = false; closing = new ArrayList<>(resources); resources.clear();
            action = notify ? invalidated : null;
        }
        for (Closeable resource : closing) try { resource.close(); } catch (IOException ignored) { }
        if (action != null) action.run();
    }
}
