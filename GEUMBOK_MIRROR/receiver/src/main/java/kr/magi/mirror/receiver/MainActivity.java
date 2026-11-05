package kr.magi.mirror.receiver;

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import kr.magi.mirror.common.Geometry;
import kr.magi.mirror.common.LabActivity;
import kr.magi.mirror.common.TlsIdentity;

public final class MainActivity extends LabActivity implements TextureView.SurfaceTextureListener {
    private TextureView video;
    private TextView cover;
    private Surface surface;
    private StreamClient client;
    private int sourceWidth=1280,sourceHeight=720;
    private Geometry.Mode mode=Geometry.Mode.FIT;
    private float centerX=.5f,centerY=.5f,zoom=2;
    private long lastPresented;
    private long generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); createControls("receiver");
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        controls.removeView(status); root.addView(status);
        FrameLayout display=new FrameLayout(this); display.setBackgroundColor(Color.BLACK);
        video=new TextureView(this); video.setSurfaceTextureListener(this);
        display.addView(video,new FrameLayout.LayoutParams(-1,-1));
        cover=new TextView(this); cover.setText("합성 영상 대기"); cover.setTextColor(Color.WHITE);
        cover.setBackgroundColor(Color.BLACK); cover.setGravity(android.view.Gravity.CENTER);
        display.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        root.addView(display,new LinearLayout.LayoutParams(-1,0,1));
        Spinner modes=new Spinner(this);
        modes.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"맞춤","채우기 · 일부 잘림","지정 영역 확대"}));
        modes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                mode=Geometry.Mode.values()[position]; transform();
            }
        });
        root.addView(modes);
        TextView address=text("Wi-Fi 연결 후 준비하세요"); TextView fingerprint=text(""); fingerprint.setTextIsSelectable(true);
        EditText host=input("휴대폰의 Wi-Fi 주소");
        EditText peer=input("휴대폰의 64자리 기기 확인 코드");
        button("기기 확인 준비",()->prepare(fingerprint,address));
        ScrollView settings=new ScrollView(this); settings.addView(controls);
        button("합성 시험 연결",()->{
            if(client!=null) return;
            try {
                if(identity==null||route==null||surface==null||!route.valid()) throw new IllegalStateException();
                TlsIdentity.decodePin(peer.getText().toString());
                long current=++generation;
                client=new StreamClient(route,identity,peer.getText().toString(),route.peer(host.getText().toString()),surface,diagnostics,
                        new StreamClient.Listener() {
                            @Override public void configured(int width,int height) {
                                main.post(()->{if(current==generation) {sourceWidth=width;sourceHeight=height;transform();}});
                            }
                            @Override public void blackout() {main.post(()->{if(current==generation) hideVideo();});}
                            @Override public void ended() {main.post(()->{if(current==generation) endSession("STREAM_ENDED");});}
                        });
                watchRoute(); client.start(); settings.setVisibility(View.GONE); message("합성 영상 연결 중");
            } catch(Exception exception) {endSession("START_FAILED");message("기기 확인 코드와 같은 Wi-Fi 주소를 확인하세요");}
        });
        slider("확대 중심 가로",50,value->{centerX=value/100f;transform();});
        slider("확대 중심 세로",50,value->{centerY=value/100f;transform();});
        slider("확대 배율",33,value->{zoom=1+3*value/100f;transform();});
        button("소켓 단절 시험",()->{if(client!=null) client.interruptSocket();});
        button("시험 중지",()->endSession("STOPPED"));
        button("진단 저장",this::saveDiagnostics);
        android.widget.Button toggle=new android.widget.Button(this); toggle.setText("연결 · 확대 설정");
        toggle.setOnClickListener(view->settings.setVisibility(settings.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        root.addView(toggle); root.addView(settings,new LinearLayout.LayoutParams(-1, Math.round(220*getResources().getDisplayMetrics().density)));
        setContentView(root);
    }
    private interface SliderAction {void changed(int value);}
    private void slider(String label,int initial,SliderAction action) {
        text(label); SeekBar seek=new SeekBar(this); seek.setMax(100); seek.setProgress(initial);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {action.changed(progress);}
        }); controls.addView(seek);
    }
    private void transform() {
        if(video==null||video.getWidth()==0||video.getHeight()==0) return;
        Geometry geometry=Geometry.calculate(sourceWidth,sourceHeight,video.getWidth(),video.getHeight(),mode,centerX,centerY,zoom);
        Matrix matrix=new Matrix();
        matrix.setScale(geometry.scale*sourceWidth/video.getWidth(),geometry.scale*sourceHeight/video.getHeight());
        matrix.postTranslate(geometry.translateX,geometry.translateY); video.setTransform(matrix);
    }
    private void hideVideo() {lastPresented=0;if(cover!=null)cover.setVisibility(View.VISIBLE);}
    @Override protected void onTick() {
        if(client!=null) {
            if(lastPresented==0||SystemClock.elapsedRealtime()-lastPresented>=500) hideVideo();
            status.setText("합성 수신 · 표시 프레임 "+diagnostics.frames+" · 인증 연결 "+diagnostics.connections);
        }
    }
    @Override protected void endSession(String reason) {
        generation++; diagnostics.event(reason);
        if(client!=null) {client.close();client=null;}
        hideVideo(); releaseIdentity(); message("시험 종료 · 기기 확인을 다시 준비하세요");
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height) {surface=new Surface(texture);transform();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height) {diagnostics.resizes++;transform();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        endSession("SURFACE_DESTROYED"); if(surface!=null)surface.release();surface=null;return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if(client!=null) {lastPresented=SystemClock.elapsedRealtime();diagnostics.frames++;cover.setVisibility(View.GONE);}
    }
}
