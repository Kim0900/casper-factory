package kr.magi.mirror.common;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocket;

public class TlsTest {
    @Test public void mutualPinsWorkAcrossTenFreshHandshakes() throws Exception {
        try(TlsIdentity server=new TlsIdentity();TlsIdentity client=new TlsIdentity()) {
            for(int i=0;i<10;i++) assertTrue(exchange(server,client,client.fingerprint(),server.fingerprint()));
        }
    }
    @Test public void wrongServerPinIsTerminal() throws Exception {
        try(TlsIdentity server=new TlsIdentity();TlsIdentity client=new TlsIdentity();TlsIdentity outsider=new TlsIdentity()) {
            assertFalse(exchange(server,client,client.fingerprint(),outsider.fingerprint()));
        }
    }
    @Test public void unregisteredClientIsTerminal() throws Exception {
        try(TlsIdentity server=new TlsIdentity();TlsIdentity client=new TlsIdentity();TlsIdentity outsider=new TlsIdentity()) {
            assertFalse(exchange(server,client,outsider.fingerprint(),server.fingerprint()));
        }
    }
    @Test public void unusedEstTrustImplementationIsNotOnRuntimeClasspath() {
        try {Class.forName("org.bouncycastle.est.jcajce.JcaJceUtils");fail();}
        catch(ClassNotFoundException expected) { }
    }
    @Test public void expiredLocalSessionCannotCreateTlsContext() throws Exception {
        TlsIdentity identity=new TlsIdentity();String pin=identity.fingerprint();identity.close();
        try {identity.context(pin);fail();} catch(IllegalStateException expected) { }
    }
    private boolean exchange(TlsIdentity server,TlsIdentity client,String serverExpectedPeer,String clientExpectedPeer) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Future<Boolean> accepted=executor.submit(()->{
                try(Socket raw=listener.accept();SSLSocket socket=(SSLSocket)server.context(serverExpectedPeer).getSocketFactory()
                        .createSocket(raw,"127.0.0.1",raw.getPort(),true)) {
                    TlsIdentity.configure(socket,false);socket.setSoTimeout(3000);socket.startHandshake();
                    socket.getOutputStream().write(42);socket.getOutputStream().flush();return true;
                } catch(Exception expected) {return false;}
            });
            boolean connected;
            try(SSLSocket socket=(SSLSocket)client.context(clientExpectedPeer).getSocketFactory().createSocket("127.0.0.1",listener.getLocalPort())) {
                TlsIdentity.configure(socket,true);socket.setSoTimeout(3000);socket.startHandshake();
                connected=socket.getInputStream().read()==42;
            } catch(Exception expected) {connected=false;}
            return accepted.get(5,TimeUnit.SECONDS)&&connected;
        } finally {executor.shutdownNow();}
    }
}
