package kr.magi.mirror.common;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;

public class ApRouteTest {
    private final Set<String> wifi = Collections.singleton("vendor-ap-bridge");
    private static InetAddress ip(int a,int b,int c,int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte)a,(byte)b,(byte)c,(byte)d});
    }
    private ApSelection.Candidate ap() throws Exception {
        return new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,1),24);
    }
    private void rejects(Set<String> names,ApSelection.Candidate... candidates) throws Exception {
        try {ApSelection.select(names,Arrays.asList(candidates));fail("Route must be rejected");}
        catch(IOException expected) { }
    }
    @Test public void authoritativeApNameWinsOverWifiCellularVpnAndUsb() throws Exception {
        ApSelection.Candidate ap=ap();
        List<ApSelection.Candidate> list=Arrays.asList(
                new ApSelection.Candidate("wlan0",3,true,ip(192,168,1,2),24),
                new ApSelection.Candidate("rmnet_data0",4,true,ip(10,0,0,2),8),
                new ApSelection.Candidate("tun0",5,true,ip(10,8,0,2),24),
                new ApSelection.Candidate("usb0",6,true,ip(192,168,88,1),24),ap);
        assertSame(ap,ApSelection.select(wifi,list));
    }
    @Test public void absentTetherCallbackHasNoHeuristicFallback() throws Exception {
        rejects(Collections.emptySet(),ap());
    }
    @Test public void onlyUpstreamPrivateWifiIsNotAnAp() throws Exception {
        rejects(wifi,new ApSelection.Candidate("wlan0",3,true,ip(192,168,1,2),24));
    }
    @Test public void downInterfaceIsRejected() throws Exception {
        rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",17,false,ip(192,168,77,1),24));
    }
    @Test public void unknownInterfaceIndexIsRejected() throws Exception {
        rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",-1,true,ip(192,168,77,1),24));
    }
    @Test public void publicLoopbackLinkLocalAndIpv6AreRejected() throws Exception {
        for(InetAddress address:Arrays.asList(ip(8,8,8,8),ip(127,0,0,1),ip(169,254,1,1),InetAddress.getByAddress(new byte[16])))
            rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",17,true,address,24));
    }
    @Test public void wildcardAndInvalidPrefixesAreRejected() throws Exception {
        for(int prefix:new int[]{0,31,32,33,-1})
            rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,1),prefix));
    }
    @Test public void subnetAndBroadcastLocalAddressesAreRejected() throws Exception {
        rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,0),24));
        rejects(wifi,new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,255),24));
    }
    @Test public void multipleApIpv4AddressesFailClosed() throws Exception {
        rejects(wifi,ap(),new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,78,1),24));
    }
    @Test public void twoActiveApInterfacesFailClosed() throws Exception {
        rejects(new java.util.HashSet<>(Arrays.asList("vendor-ap-bridge","ap2")),ap(),
                new ApSelection.Candidate("ap2",18,true,ip(192,168,78,1),24));
    }
    @Test public void identicalSnapshotDoesNotRevoke() throws Exception {
        try(RouteLease lease=new RouteLease()) {
            ApSelection.checkLease(ap(),wifi,Collections.singletonList(ap()),lease);assertTrue(lease.valid());
        }
    }
    @Test public void addressPrefixOrInterfaceRecreationRevokes() throws Exception {
        for(ApSelection.Candidate change:Arrays.asList(
                new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,2),24),
                new ApSelection.Candidate("vendor-ap-bridge",17,true,ip(192,168,77,1),25),
                new ApSelection.Candidate("vendor-ap-bridge",18,true,ip(192,168,77,1),24))) {
            try(RouteLease lease=new RouteLease()) {
                ApSelection.checkLease(ap(),wifi,Collections.singletonList(change),lease);assertFalse(lease.valid());
            }
        }
    }
    @Test public void hotspotReturnNeverRevivesOldLease() throws Exception {
        RouteLease lease=new RouteLease();AtomicInteger endings=new AtomicInteger();lease.watch(endings::incrementAndGet);
        ApSelection.checkLease(ap(),Collections.emptySet(),Collections.emptyList(),lease);
        ApSelection.checkLease(ap(),wifi,Collections.singletonList(ap()),lease);
        lease.invalidate();assertFalse(lease.valid());assertEquals(1,endings.get());
    }
    @Test public void missingAddressRevokesAndClosesExistingSocket() throws Exception {
        try(RouteLease lease=new RouteLease()) {
            Socket socket=lease.own(new Socket());
            ApSelection.checkLease(ap(),wifi,Collections.emptyList(),lease);
            assertFalse(lease.valid());assertTrue(socket.isClosed());
        }
    }
    @Test public void resourceRegisteredAfterRevocationIsClosed() throws Exception {
        RouteLease lease=new RouteLease();lease.invalidate();Socket late=new Socket();
        try {lease.own(late);fail();}catch(IOException expected) { }
        assertTrue(late.isClosed());
    }
    @Test public void lateWatcherReceivesTerminalStateOnce() {
        RouteLease lease=new RouteLease();lease.invalidate();AtomicInteger endings=new AtomicInteger();
        lease.watch(endings::incrementAndGet);lease.invalidate();assertEquals(1,endings.get());
    }
    @Test public void peerRestrictionRejectsSelfOutsideSubnetBroadcastAndNames() throws Exception {
        try(LocalRoute route=new LocalRoute(ip(192,168,77,1),24) {
            @Override public boolean valid() {return lease.valid();}
            @Override public void watch(Runnable action) {lease.watch(action);}
        }) {
            assertEquals(ip(192,168,77,2),route.peer("192.168.77.2"));
            for(String value:new String[]{"192.168.77.1","192.168.78.2","192.168.77.0","192.168.77.255","8.8.8.8","localhost","192.168.077.2"})
                try {route.peer(value);fail(value);}catch(IOException expected) { }
        }
    }
    @Test public void hotspotOffClosesRealListenerAndAuthenticatedTlsBeforeNotification() throws Exception {
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try(RouteLease lease=new RouteLease();TlsIdentity server=new TlsIdentity();TlsIdentity client=new TlsIdentity()) {
            ServerSocket listener=lease.own(new ServerSocket(0,1,InetAddress.getLoopbackAddress()));
            Future<SSLSocket> accepted=thread.submit(()->{
                Socket raw=lease.own(listener.accept());
                SSLSocket tls=lease.own((SSLSocket)server.context(client.fingerprint()).getSocketFactory()
                        .createSocket(raw,"127.0.0.1",raw.getPort(),true));
                TlsIdentity.configure(tls,false);tls.setSoTimeout(3000);tls.startHandshake();
                tls.getOutputStream().write(42);tls.getOutputStream().flush();return tls;
            });
            try(SSLSocket remote=(SSLSocket)client.context(server.fingerprint()).getSocketFactory()
                    .createSocket("127.0.0.1",listener.getLocalPort())) {
                TlsIdentity.configure(remote,true);remote.setSoTimeout(3000);remote.startHandshake();
                assertEquals(42,remote.getInputStream().read());SSLSocket active=accepted.get(5,TimeUnit.SECONDS);
                AtomicInteger endings=new AtomicInteger();
                lease.watch(()->{assertTrue(listener.isClosed());assertTrue(active.isClosed());endings.incrementAndGet();});
                // The same production snapshot-to-lease function used by the Android AP monitor.
                ApSelection.checkLease(ap(),Collections.emptySet(),Collections.emptyList(),lease);
                assertFalse(lease.valid());assertTrue(listener.isClosed());assertTrue(active.isClosed());assertEquals(1,endings.get());
                ApSelection.checkLease(ap(),wifi,Collections.singletonList(ap()),lease);assertFalse(lease.valid());
            }
        } finally {thread.shutdownNow();}
    }
}
