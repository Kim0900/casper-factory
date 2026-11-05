package kr.magi.mirror.receiver;

import android.os.SystemClock;
import android.view.Surface;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import kr.magi.mirror.common.Diagnostics;
import kr.magi.mirror.common.RetryBudget;
import kr.magi.mirror.common.TlsIdentity;
import kr.magi.mirror.common.WifiRoute;
import kr.magi.mirror.common.Wire;

final class StreamClient implements AutoCloseable {
    interface Listener { void configured(int width,int height); void epoch(long presentationUs); void blackout(); void ended(); }
    private final WifiRoute route;
    private final TlsIdentity identity;
    private final String peerPin;
    private final InetAddress peer;
    private final Surface surface;
    private final Diagnostics diagnostics;
    private final Listener listener;
    private volatile boolean running=true;
    private volatile Socket connection;

    StreamClient(WifiRoute route,TlsIdentity identity,String peerPin,InetAddress peer,Surface surface,
                 Diagnostics diagnostics,Listener listener) {
        this.route=route; this.identity=identity; this.peerPin=peerPin; this.peer=peer;
        this.surface=surface; this.diagnostics=diagnostics; this.listener=listener;
    }
    void start() { new Thread(this::run,"synthetic-client").start(); }
    void interruptSocket() { closeSocket(); }
    private void run() {
        RetryBudget outage=null;
        try {
            while(running&&route.valid()) {
                VideoDecoder decoder=null;
                try {
                    int timeout=outage==null?3500:outage.connectTimeout(SystemClock.elapsedRealtime());
                    Socket raw=route.connect(peer,38443,timeout); connection=raw;
                    if(!running) break;
                    // A new context forbids TLS session resumption from bypassing a fresh pin check.
                    SSLContext context=identity.context(peerPin);
                    SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(raw,peer.getHostAddress(),38443,true);
                    connection=tls; TlsIdentity.configure(tls,true); tls.setSoTimeout(timeout);
                    try {tls.startHandshake();} catch(javax.net.ssl.SSLException exception) {throw new SSLHandshakeException("Peer authentication failed");}
                    if(!running||!route.valid()) break;
                    diagnostics.connections++; diagnostics.event("TLS_AUTHENTICATED");
                    tls.setSoTimeout(3500);
                    DataInputStream input=new DataInputStream(tls.getInputStream());
                    Wire.Packet config=Wire.read(input);
                    if(config.type!=Wire.CONFIG) throw new Wire.ProtocolException("Unexpected frame sequence");
                    decoder=new VideoDecoder(config.data,surface,diagnostics); diagnostics.decoderCreations++;
                    listener.configured(decoder.width,decoder.height);
                    tls.setSoTimeout(750);
                    boolean needsIdr=true;
                    long lastSequence=-1;
                    while(running&&route.valid()) {
                        Wire.Packet packet=Wire.read(input);
                        if(packet.type!=Wire.FRAME||packet.sequence<=lastSequence) throw new Wire.ProtocolException("Unexpected frame sequence");
                        if(needsIdr&&packet.flags!=1) throw new Wire.ProtocolException("Unexpected frame sequence");
                        if(needsIdr) listener.epoch(packet.presentationUs);
                        needsIdr=false; lastSequence=packet.sequence;
                        if(decoder.feed(packet)) outage=null;
                    }
                } catch(SSLHandshakeException exception) {
                    diagnostics.event("TLS_REJECTED_TERMINAL"); break;
                } catch(Wire.ProtocolException exception) {
                    diagnostics.event("PROTOCOL_REJECTED_TERMINAL"); break;
                } catch(IOException exception) {
                    listener.blackout();
                    if(!running||!route.valid()) break;
                    if(outage==null) outage=new RetryBudget(SystemClock.elapsedRealtime());
                    long delay=outage.nextDelay(RetryBudget.Failure.SOCKET,SystemClock.elapsedRealtime());
                    if(delay<0) { diagnostics.event("RETRY_EXHAUSTED"); break; }
                    diagnostics.event("SOCKET_RETRY");
                    // Close the old decoder and transport before waiting or authenticating again.
                    if(decoder!=null) {decoder.close(); decoder=null;}
                    closeSocket();
                    Thread.sleep(delay);
                } finally {
                    if(decoder!=null) decoder.close();
                    closeSocket(); listener.blackout();
                }
            }
        } catch(Exception exception) { if(running) diagnostics.event("RECEIVER_ENDED"); }
        finally { boolean notify=running; close(); if(notify) listener.ended(); }
    }
    private void closeSocket() {
        Socket socket=connection;
        if(socket!=null) try {socket.close();} catch(IOException ignored) { }
        connection=null;
    }
    @Override public void close() { running=false; closeSocket(); listener.blackout(); }
}
