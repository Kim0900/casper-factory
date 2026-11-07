package kr.magi.mirror.common;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.InetSocketAddress;

/** A selected Wi-Fi network and exact local address. No name resolution or process-wide routing. */
public final class WifiRoute extends LocalRoute {
    private final ConnectivityManager manager;
    private final Network network;
    private ConnectivityManager.NetworkCallback callback;

    private WifiRoute(ConnectivityManager manager, Network network, LinkAddress link) {
        super(link.getAddress(), link.getPrefixLength());
        this.manager = manager; this.network = network;
    }

    public static WifiRoute select(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (capabilities == null || properties == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address && link.getAddress().isSiteLocalAddress()) {
                    return new WifiRoute(manager, network, link);
                }
            }
        }
        throw new IOException("A private Wi-Fi IPv4 connection is required");
    }

    public boolean valid() {
        if (!lease.valid()) return false;
        try {
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        LinkProperties properties = manager.getLinkProperties(network);
        if (capabilities == null || properties == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false;
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (address.equals(link.getAddress()) && prefix == link.getPrefixLength()) return true;
        }
        return false;
        } catch(SecurityException exception) {return false;}
    }

    public Socket connect(InetAddress peer, int port, int timeout) throws IOException {
        if (!valid() || !allows(peer) || timeout <= 0) throw new IOException("Selected network unavailable");
        Socket socket = lease.own(network.getSocketFactory().createSocket());
        try {
            socket.connect(new InetSocketAddress(peer, port), timeout);
            if (!valid() || !address.equals(socket.getLocalAddress())) throw new IOException("Network changed");
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException exception) { socket.close(); throw exception; }
    }

    public void watch(Runnable invalidated) {
        lease.watch(invalidated);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network lost) { if (network.equals(lost)) lease.invalidate(); }
            @Override public void onLinkPropertiesChanged(Network changed, LinkProperties properties) {
                if (network.equals(changed) && !valid()) lease.invalidate();
            }
            @Override public void onCapabilitiesChanged(Network changed, NetworkCapabilities capabilities) {
                if (network.equals(changed) && !valid()) lease.invalidate();
            }
        };
        manager.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
    }

    @Override public void close() {
        super.close();
        if (callback != null) {
            manager.unregisterNetworkCallback(callback);
            callback = null;
        }
    }
}
