package kr.magi.mirror.sender;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.TetheringInterface;
import android.net.TetheringManager;
import android.os.Build;
import java.io.IOException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.magi.mirror.common.ApSelection;
import kr.magi.mirror.common.LocalRoute;

/** Sender only. Reads public Android 16 tethered-Wi-Fi status; never enables tethering. */
interface HotspotMonitor extends AutoCloseable {
    LocalRoute select() throws IOException;
    @Override void close();

    static HotspotMonitor start(Context context) throws IOException {
        if (Build.VERSION.SDK_INT < 36) throw new IOException("Hotspot mode requires Android 16");
        return new Api36(context);
    }

    @TargetApi(36)
    final class Api36 implements HotspotMonitor {
        private final TetheringManager manager;
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        private final TetheringManager.TetheringEventCallback callback;
        private Set<String> wifiNames = Collections.emptySet();
        private List<ApSelection.Candidate> addresses = Collections.emptyList();
        private boolean ready, closed;
        private long sampledNanos;
        private HotspotRoute selected;

        Api36(Context context) throws IOException {
            manager = context.getSystemService(TetheringManager.class);
            if (manager == null) { worker.shutdownNow(); throw new IOException("Tethering status unavailable"); }
            callback = new TetheringManager.TetheringEventCallback() {
                @Override public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
                    Set<String> names = new HashSet<>();
                    for (TetheringInterface item : interfaces)
                        if (item.getType() == TetheringManager.TETHERING_WIFI) names.add(item.getInterface());
                    synchronized (Api36.this) {
                        if (closed) return;
                        // Revoke synchronously on removal, before the next address poll or UI message.
                        if (selected != null && !names.equals(wifiNames)) selected.revoke();
                        wifiNames = Collections.unmodifiableSet(names); ready = true;
                        refresh();
                    }
                }
            };
            try {
                manager.registerTetheringEventCallback(worker, callback);
                worker.scheduleWithFixedDelay(this::refresh, 0, 100, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                worker.shutdownNow();
                try { manager.unregisterTetheringEventCallback(callback); } catch (RuntimeException ignored) { }
                throw new IOException("Cannot observe Wi-Fi tethering", exception);
            }
        }

        private synchronized void refresh() {
            if (closed) return;
            List<ApSelection.Candidate> snapshot = new ArrayList<>();
            try {
                // Inspect only OS-certified Wi-Fi AP names. Never guess wlan/ap/rmnet prefixes.
                for (String name : wifiNames) {
                    NetworkInterface network = NetworkInterface.getByName(name);
                    if (network == null || !network.isUp() || network.isLoopback()) continue;
                    for (InterfaceAddress link : network.getInterfaceAddresses())
                        snapshot.add(new ApSelection.Candidate(name, network.getIndex(), true,
                                link.getAddress(), link.getNetworkPrefixLength()));
                }
                addresses = Collections.unmodifiableList(snapshot); sampledNanos = System.nanoTime();
                if (selected != null) selected.check(wifiNames, addresses);
            } catch (Exception exception) {
                addresses = Collections.emptyList(); sampledNanos = 0;
                if (selected != null) selected.revoke();
            }
        }

        @Override public synchronized LocalRoute select() throws IOException {
            if (closed || !ready) throw new IOException("Wi-Fi tethering status not ready");
            refresh();
            ApSelection.Candidate anchor = ApSelection.select(wifiNames, addresses);
            if (selected != null) selected.close();
            selected = new HotspotRoute(this, anchor);
            return selected;
        }

        private synchronized boolean current(HotspotRoute route) {
            boolean valid = !closed && selected == route && sampledNanos != 0
                    && System.nanoTime() - sampledNanos <= TimeUnit.MILLISECONDS.toNanos(500)
                    && ApSelection.unchanged(route.anchor, wifiNames, addresses);
            if (!valid) route.revoke();
            return valid;
        }

        @Override public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                if (selected != null) selected.revoke();
            }
            try { manager.unregisterTetheringEventCallback(callback); } catch (RuntimeException ignored) { }
            worker.shutdownNow();
        }

        private static final class HotspotRoute extends LocalRoute {
            private final Api36 owner;
            private final ApSelection.Candidate anchor;
            HotspotRoute(Api36 owner, ApSelection.Candidate anchor) {
                super(anchor.address, anchor.prefix); this.owner = owner; this.anchor = anchor;
            }
            void revoke() { lease.invalidate(); }
            void check(Set<String> names, List<ApSelection.Candidate> candidates) {
                ApSelection.checkLease(anchor, names, candidates, lease);
            }
            @Override public boolean valid() { return lease.valid() && owner.current(this); }
            @Override public void watch(Runnable invalidated) { lease.watch(invalidated); }
            @Override public String label() { return "핫스팟 AP 주소"; }
            @Override public boolean hotspot() { return true; }
        }
    }
}
