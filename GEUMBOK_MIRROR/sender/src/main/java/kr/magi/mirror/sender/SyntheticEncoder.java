package kr.magi.mirror.sender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.magi.mirror.common.Wire;
import kr.magi.mirror.common.CodecPolicy;
import kr.magi.mirror.common.Diagnostics;

/** One static GPU texture plus a changing frame marker. No screen-capture APIs. */
final class SyntheticEncoder implements AutoCloseable {
    interface Listener { void config(byte[] data); void frame(Wire.Packet packet); void preview(long frame); void failed(); }
    final int width, height, fps, bitrate;
    final Bitmap map;
    private final Listener listener;
    private final Diagnostics diagnostics;
    private final AtomicBoolean syncRequested = new AtomicBoolean();
    private final AtomicBoolean lowerBitrate = new AtomicBoolean();
    private volatile boolean running = true;
    private MediaCodec codec;
    private Surface input;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program, texture;
    private FloatBuffer vertices, coordinates;

    SyntheticEncoder(int width, int height, int fps, int bitrate, Diagnostics diagnostics, Listener listener) {
        this.width=width; this.height=height; this.fps=fps; this.bitrate=bitrate; this.listener=listener; this.diagnostics=diagnostics;
        map = createMap(width, height);
    }
    void start() { new Thread(this::run, "synthetic-encoder").start(); }
    void requestKeyFrame() { syncRequested.set(true); }
    void lowerBitrate() { lowerBitrate.set(true); }

    static Bitmap createMap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(224, 235, 218));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE); paint.setStrokeWidth(22);
        for (int x=0; x<width; x+=width/8) canvas.drawLine(x,0,x,height,paint);
        for (int y=0; y<height; y+=height/6) canvas.drawLine(0,y,width,y,paint);
        paint.setStrokeWidth(14); paint.setColor(Color.rgb(30,110,220));
        canvas.drawLine(width*.2f,height*.8f,width*.2f,height*.4f,paint);
        canvas.drawLine(width*.2f,height*.4f,width*.75f,height*.4f,paint);
        canvas.drawLine(width*.75f,height*.4f,width*.75f,height*.15f,paint);
        paint.setColor(Color.rgb(20,40,50)); paint.setTextSize(height/18f);
        canvas.drawText("SYNTHETIC MAP / NO REAL LOCATION",width*.03f,height*.14f,paint);
        paint.setTextSize(height/25f);
        for(int i=1;i<=6;i++) canvas.drawText("GRID " + i,width*.04f+(i-1)*width*.15f,height*.65f,paint);
        paint.setColor(Color.RED); canvas.drawCircle(width*.75f,height*.15f,12,paint);
        return bitmap;
    }

    private void run() {
        try {
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec=MediaCodec.createEncoderByType("video/avc");
            diagnostics.event("ENCODER_"+CodecPolicy.requireHardware(codec));
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            input=codec.createInputSurface(); codec.start(); setupGl();
            long frame=0, next=SystemClock.elapsedRealtimeNanos();
            while(running) {
                if(syncRequested.getAndSet(false)) {
                    Bundle bundle=new Bundle(); bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0); codec.setParameters(bundle);
                }
                if(lowerBitrate.getAndSet(false)) {
                    Bundle bundle=new Bundle(); bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE,bitrate/2); codec.setParameters(bundle);
                }
                draw(frame);
                long presentation=SystemClock.elapsedRealtimeNanos();
                EGLExt.eglPresentationTimeANDROID(display,surface,presentation);
                if(!EGL14.eglSwapBuffers(display,surface)) throw new IllegalStateException("EGL swap failed");
                listener.preview(frame++);
                drain();
                next += 1_000_000_000L/fps;
                long remaining=next-SystemClock.elapsedRealtimeNanos();
                if(remaining>0) Thread.sleep(remaining/1_000_000L,(int)(remaining%1_000_000L));
                else next=SystemClock.elapsedRealtimeNanos();
            }
        } catch(Exception exception) { if(running) listener.failed(); }
        finally {
            running=false;
            if(display!=EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(surface!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface);
                if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
            }
            if(codec!=null) { try {codec.stop();} catch(IllegalStateException ignored) { } codec.release(); }
            if(input!=null) input.release();
        }
    }

    private void drain() throws Exception {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        int index;
        while((index=codec.dequeueOutputBuffer(info,0))!=MediaCodec.INFO_TRY_AGAIN_LATER) {
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format=codec.getOutputFormat();
                byte[] first=bytes(format.getByteBuffer("csd-0"));
                byte[] second=bytes(format.getByteBuffer("csd-1"));
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream out=new java.io.DataOutputStream(buffer);
                out.writeInt(width); out.writeInt(height); out.writeInt(first.length); out.write(first);
                out.writeInt(second.length); out.write(second); listener.config(buffer.toByteArray());
            } else if(index>=0) {
                try {
                    if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                        ByteBuffer data=codec.getOutputBuffer(index);
                        if(data==null) throw new IllegalStateException("Missing encoded buffer");
                        data.position(info.offset); data.limit(info.offset+info.size);
                        byte[] encoded=new byte[info.size]; data.get(encoded);
                        listener.frame(new Wire.Packet(Wire.FRAME,(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0?1:0,
                                info.presentationTimeUs,info.presentationTimeUs,encoded));
                    }
                } finally { codec.releaseOutputBuffer(index,false); }
            }
        }
    }
    private static byte[] bytes(ByteBuffer buffer) {
        if(buffer==null) return new byte[0];
        ByteBuffer copy=buffer.duplicate(); byte[] bytes=new byte[copy.remaining()]; copy.get(bytes); return bytes;
    }
    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0); return buffer;
    }
    private void setupGl() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if(!EGL14.eglInitialize(display,new int[2],0,new int[2],0)) throw new IllegalStateException("EGL init failed");
        EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
        int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
        if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0) throw new IllegalStateException("No recordable EGL config");
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        surface=EGL14.eglCreateWindowSurface(display,configs[0],input,new int[]{EGL14.EGL_NONE},0);
        if(!EGL14.eglMakeCurrent(display,surface,surface,context)) throw new IllegalStateException("EGL context failed");
        int vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.);uv=t;}");
        int fragment=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 uv; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,uv);}");
        program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment); GLES20.glLinkProgram(program);
        int[] linked=new int[1]; GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0);
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        if(linked[0]!=GLES20.GL_TRUE) throw new IllegalStateException("GL link failed");
        int[] textures=new int[1]; GLES20.glGenTextures(1,textures,0); texture=textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,map,0);
        vertices=floats(new float[]{-1,-1,1,-1,-1,1,1,1});
        coordinates=floats(new float[]{0,1,1,1,0,0,1,0});
    }
    private static int shader(int type,String source) {
        int shader=GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] compiled=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,compiled,0);
        if(compiled[0]!=GLES20.GL_TRUE) throw new IllegalStateException("GL compile failed");
        return shader;
    }
    private void draw(long frame) {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program);
        int position=GLES20.glGetAttribLocation(program,"p"), uv=GLES20.glGetAttribLocation(program,"t");
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices);
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,coordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        for(int bit=0;bit<24;bit++) {
            boolean on=(frame&(1L<<bit))!=0;
            GLES20.glScissor(bit*width/24,0,width/24,height/12);
            GLES20.glClearColor(on?1:0,on?1:0,on?1:0,1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        if(GLES20.glGetError()!=GLES20.GL_NO_ERROR) throw new IllegalStateException("GL rendering failed");
    }
    @Override public void close() { running=false; }
}
