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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.Arrays;

/** A selected Wi-Fi network and exact local address. No name resolution or process-wide routing. */
public final class WifiRoute implements AutoCloseable {
    private final ConnectivityManager manager;
    private final Network network;
    public final InetAddress address;
    private final int prefix;
    private ConnectivityManager.NetworkCallback callback;

    private WifiRoute(ConnectivityManager manager, Network network, LinkAddress link) {
        this.manager = manager; this.network = network;
        address = link.getAddress(); prefix = link.getPrefixLength();
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

    public InetAddress peer(String literal) throws IOException {
        String[] parts = literal.trim().split("\\.", -1);
        if (parts.length != 4) throw new IOException("Use a literal IPv4 address");
        byte[] bytes = new byte[4];
        try {
            for (int i=0; i<4; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new NumberFormatException();
                int number = Integer.parseInt(parts[i]);
                if (number > 255) throw new NumberFormatException();
                bytes[i] = (byte) number;
            }
        } catch (NumberFormatException exception) { throw new IOException("Invalid IPv4 address"); }
        InetAddress peer = InetAddress.getByAddress(bytes);
        if (!allows(peer)) throw new IOException("Peer must be directly on the selected Wi-Fi subnet");
        return peer;
    }

    public boolean allows(InetAddress peer) {
        if (!(peer instanceof Inet4Address) || !peer.isSiteLocalAddress() || peer.equals(address)) return false;
        byte[] local = address.getAddress(), remote = peer.getAddress();
        int bits = prefix;
        for (int i=0; i<local.length; i++) {
            int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : 255 << (8-bits) & 255;
            if ((local[i] & mask) != (remote[i] & mask)) return false;
            bits -= 8;
        }
        if(prefix < 31) {
            int remoteInt=java.nio.ByteBuffer.wrap(remote).getInt();
            int hostMask=(int)(0xffffffffL >>> prefix);
            int host=remoteInt & hostMask;
            if(host==0 || host==hostMask) return false;
        }
        return !peer.isMulticastAddress() && !peer.isAnyLocalAddress() && !peer.isLoopbackAddress();
    }

    public Socket connect(InetAddress peer, int port, int timeout) throws IOException {
        if (!valid() || !allows(peer) || timeout <= 0) throw new IOException("Selected network unavailable");
        Socket socket = network.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(peer, port), timeout);
            if (!valid() || !address.equals(socket.getLocalAddress())) throw new IOException("Network changed");
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException exception) { socket.close(); throw exception; }
    }

    public ServerSocket listen(int port) throws IOException {
        if (!valid()) throw new IOException("Selected network unavailable");
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(address, port), 1);
        server.setSoTimeout(250);
        return server;
    }

    public void watch(Runnable invalidated) {
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network lost) { if (network.equals(lost)) invalidated.run(); }
            @Override public void onLinkPropertiesChanged(Network changed, LinkProperties properties) {
                if (network.equals(changed) && !valid()) invalidated.run();
            }
            @Override public void onCapabilitiesChanged(Network changed, NetworkCapabilities capabilities) {
                if (network.equals(changed) && !valid()) invalidated.run();
            }
        };
        manager.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
    }

    @Override public void close() {
        if (callback != null) {
            manager.unregisterNetworkCallback(callback);
            callback = null;
        }
    }
}
