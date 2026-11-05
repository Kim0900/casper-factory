package kr.magi.mirror.receiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import kr.magi.mirror.common.Wire;

final class VideoDecoder implements AutoCloseable {
    final int width,height;
    private final MediaCodec codec;
    VideoDecoder(byte[] configuration,Surface surface) throws Exception {
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(configuration));
        width=input.readInt(); height=input.readInt();
        if(!((width==1280&&height==720)||(width==960&&height==540))) throw new Wire.ProtocolException("Unsupported profile");
        byte[] first=readCsd(input,false), second=readCsd(input,true);
        if(input.available()!=0) throw new Wire.ProtocolException("Trailing configuration");
        MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
        format.setByteBuffer("csd-0",ByteBuffer.wrap(first)); if(second.length>0) format.setByteBuffer("csd-1",ByteBuffer.wrap(second));
        codec=MediaCodec.createDecoderByType("video/avc");
        try { codec.configure(format,surface,null,0); codec.start(); }
        catch(Exception exception) { codec.release(); throw exception; }
    }
    private static byte[] readCsd(DataInputStream input,boolean allowEmpty) throws IOException {
        int length=input.readInt();
        if(length<(allowEmpty?0:1)||length>4096) throw new Wire.ProtocolException("Invalid AVC configuration");
        byte[] bytes=new byte[length]; input.readFully(bytes); return bytes;
    }
    boolean feed(Wire.Packet frame) throws IOException {
        int input=codec.dequeueInputBuffer(20000);
        if(input<0) throw new Wire.ProtocolException("Decoder input stalled");
        ByteBuffer buffer=codec.getInputBuffer(input);
        if(buffer==null||buffer.capacity()<frame.data.length) throw new Wire.ProtocolException("Decoder buffer bound");
        buffer.clear(); buffer.put(frame.data);
        codec.queueInputBuffer(input,0,frame.data.length,frame.presentationUs,0);
        boolean rendered=false;
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        int output;
        while((output=codec.dequeueOutputBuffer(info,0))!=MediaCodec.INFO_TRY_AGAIN_LATER) {
            if(output>=0) { codec.releaseOutputBuffer(output,true); rendered=true; }
        }
        return rendered;
    }
    @Override public void close() {
        try {codec.stop();} catch(IllegalStateException ignored) { }
        codec.release();
    }
}
