package kr.magi.mirror.common;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Exact local IPv4 plus a terminal lifetime. No default-network or wildcard fallback. */
public abstract class LocalRoute implements AutoCloseable {
    public final InetAddress address;
    protected final int prefix;
    protected final RouteLease lease = new RouteLease();
    protected LocalRoute(InetAddress address, int prefix) {
        if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress() || prefix < 1 || prefix > 32)
            throw new IllegalArgumentException("Private IPv4 route required");
        this.address = address; this.prefix = prefix;
    }
    public abstract boolean valid();
    public abstract void watch(Runnable invalidated);
    public String label() { return "Wi-Fi 주소"; }
    public boolean hotspot() { return false; }
    public Socket connect(InetAddress peer, int port, int timeout) throws IOException {
        throw new IOException("This route is sender-only");
    }
    public final Socket protect(Socket socket) throws IOException {
        lease.own(socket);
        if (!valid() || !address.equals(socket.getLocalAddress()) || !allows(socket.getInetAddress())) {
            socket.close(); throw new IOException("Socket does not belong to selected route");
        }
        return socket;
    }
    public ServerSocket listen(int port) throws IOException {
        if (!valid()) throw new IOException("Selected route unavailable");
        ServerSocket server = lease.own(new ServerSocket());
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address, port), 1);
            server.setSoTimeout(250);
            if (!valid()) throw new IOException("Selected route changed");
            return server;
        } catch (IOException exception) { server.close(); throw exception; }
    }
    public final InetAddress peer(String literal) throws IOException {
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
        if (!allows(peer)) throw new IOException("Peer must be on the selected local subnet");
        return peer;
    }
    public final boolean allows(InetAddress peer) {
        if (!(peer instanceof Inet4Address) || !peer.isSiteLocalAddress() || peer.equals(address)) return false;
        byte[] local = address.getAddress(), remote = peer.getAddress();
        int bits = prefix;
        for (int i=0; i<local.length; i++) {
            int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : 255 << (8-bits) & 255;
            if ((local[i] & mask) != (remote[i] & mask)) return false;
            bits -= 8;
        }
        if (prefix < 31) {
            int remoteInt = java.nio.ByteBuffer.wrap(remote).getInt();
            int hostMask = (int)(0xffffffffL >>> prefix), host = remoteInt & hostMask;
            if (host == 0 || host == hostMask) return false;
        }
        return !peer.isMulticastAddress() && !peer.isAnyLocalAddress() && !peer.isLoopbackAddress();
    }
    @Override public void close() { lease.close(); }
}
