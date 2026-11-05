package kr.magi.mirror.sender;

import android.os.SystemClock;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import kr.magi.mirror.common.Diagnostics;
import kr.magi.mirror.common.TlsIdentity;
import kr.magi.mirror.common.WifiRoute;
import kr.magi.mirror.common.Wire;

final class StreamServer implements AutoCloseable {
    static final int PORT=38443;
    private final WifiRoute route;
    private final TlsIdentity identity;
    private final String peerPin;
    private final Diagnostics diagnostics;
    private final Runnable failed;
    private final SyntheticEncoder encoder;
    private final ArrayBlockingQueue<Wire.Packet> queue=new ArrayBlockingQueue<>(2);
    private volatile boolean running=true, authenticated;
    private volatile byte[] configuration;
    private volatile SSLSocket client;
    private volatile ServerSocket server;

    StreamServer(WifiRoute route,TlsIdentity identity,String peerPin,Diagnostics diagnostics,
                 int width,int height,int fps,int bitrate,SyntheticEncoder.Listener preview,Runnable failed) {
        this.route=route; this.identity=identity; this.peerPin=peerPin; this.diagnostics=diagnostics; this.failed=failed;
        encoder=new SyntheticEncoder(width,height,fps,bitrate,new SyntheticEncoder.Listener() {
            @Override public void config(byte[] data) { configuration=data; }
            @Override public void frame(Wire.Packet packet) {
                diagnostics.frames++;
                if(authenticated && !queue.offer(packet)) { diagnostics.event("QUEUE_OVERFLOW"); disconnect(); }
            }
            @Override public void preview(long frame) { preview.preview(frame); }
            @Override public void failed() { failed.run(); }
        });
    }
    android.graphics.Bitmap map() { return encoder.map; }
    void start() { new Thread(this::run,"synthetic-server").start(); }
    void lowerBitrate() { encoder.lowerBitrate(); }
    private void run() {
        boolean sourceStarted=false;
        long deadline=SystemClock.elapsedRealtime()+120000;
        try {
            server=route.listen(PORT);
            while(running && route.valid() && SystemClock.elapsedRealtime()<deadline) {
                Socket accepted;
                try { accepted=server.accept(); } catch(SocketTimeoutException exception) { continue; }
                if(!route.allows(accepted.getInetAddress())) { accepted.close(); continue; }
                accepted.setTcpNoDelay(true);
                try {
                    // Listener is bound to the selected Wi-Fi address, never 0.0.0.0.
                    SSLContext context=identity.context(peerPin);
                    SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(accepted,
                            accepted.getInetAddress().getHostAddress(),accepted.getPort(),true);
                    client=tls; TlsIdentity.configure(tls,false); tls.setSoTimeout(3500);
                    try {tls.startHandshake();} catch(javax.net.ssl.SSLException exception) {throw new SSLHandshakeException("Peer authentication failed");}
                    if(!route.valid() || !running) throw new IOException("Network ended");
                    diagnostics.connections++; diagnostics.event("TLS_AUTHENTICATED");
                    if(!sourceStarted) { encoder.start(); diagnostics.encoderCreations++; sourceStarted=true; }
                    queue.clear();
                    long configDeadline=SystemClock.elapsedRealtime()+2500;
                    while(running && configuration==null && SystemClock.elapsedRealtime()<configDeadline) Thread.sleep(10);
                    if(configuration==null) throw new IOException("Encoder configuration unavailable");
                    try(DataOutputStream output=new DataOutputStream(tls.getOutputStream())) {
                        Wire.write(output,new Wire.Packet(Wire.CONFIG,0,0,0,configuration));
                        long freshAfterUs=SystemClock.elapsedRealtimeNanos()/1000;
                        authenticated=true; encoder.requestKeyFrame();
                        boolean waitingForIdr=true;
                        while(running && route.valid() && !tls.isClosed()) {
                            Wire.Packet frame=queue.poll(1000,TimeUnit.MILLISECONDS);
                            if(frame==null) throw new IOException("Encoder stalled");
                            if(frame.presentationUs<freshAfterUs || (waitingForIdr && frame.flags!=1)) continue;
                            waitingForIdr=false;
                            Wire.write(output,frame);
                        }
                    }
                } catch(SSLHandshakeException exception) {
                    diagnostics.event("TLS_REJECTED_TERMINAL"); throw exception;
                } catch(IOException exception) {
                    if(running) diagnostics.event("SOCKET_OUTAGE");
                } finally {
                    authenticated=false; queue.clear(); disconnect(); accepted.close();
                }
                deadline=SystemClock.elapsedRealtime()+15000;
            }
        } catch(Exception exception) { if(running) diagnostics.event("SERVER_ENDED"); }
        finally { boolean notify=running; close(); if(notify) failed.run(); }
    }
    private void disconnect() {
        authenticated=false;
        SSLSocket socket=client;
        if(socket!=null) try {socket.close();} catch(IOException ignored) { }
        client=null;
    }
    @Override public void close() {
        running=false; disconnect(); queue.clear(); encoder.close();
        ServerSocket listener=server;
        if(listener!=null) try {listener.close();} catch(IOException ignored) { }
    }
}
