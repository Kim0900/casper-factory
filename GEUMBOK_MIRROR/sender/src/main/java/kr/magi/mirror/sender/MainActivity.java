package kr.magi.mirror.sender;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.AdapterView;
import java.io.IOException;
import kr.magi.mirror.common.LabActivity;
import kr.magi.mirror.common.LocalRoute;
import kr.magi.mirror.common.WifiRoute;
import kr.magi.mirror.common.TlsIdentity;
import kr.magi.mirror.common.Wire;

public final class MainActivity extends LabActivity {
    private volatile StreamServer server;
    private HotspotMonitor hotspot;
    private Spinner routeMode;
    private EditText peer;
    private Preview preview;
    private long generation;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); createControls("sender");
        text("금복 미러 · 합성 영상 송신");
        routeMode=new Spinner(this);
        routeMode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"휴대폰 핫스팟(AP) · Android 16 이상","공용 AP · 두 기기 같은 Wi-Fi"}));
        routeMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                endSession("ROUTE_MODE_SELECTED");
            }
        });
        controls.addView(routeMode);
        TextView address=text("Wi-Fi 연결 후 준비하세요");
        TextView fingerprint=text(""); fingerprint.setTextIsSelectable(true);
        peer=input("태블릿의 64자리 기기 확인 코드");
        Spinner profile=new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1280×720 / 15fps / 1.5Mbps","960×540 / 10fps / 0.8Mbps"}));
        controls.addView(profile);
        button("기기 확인 준비",()->prepare(fingerprint,address));
        button("합성 시험 시작",()->{
            if(server!=null) return;
            try {
                if(identity==null || route==null || !route.valid()) throw new IllegalStateException();
                TlsIdentity.decodePin(peer.getText().toString());
                boolean low=profile.getSelectedItemPosition()==1;
                long current=++generation;
                server=new StreamServer(route,identity,peer.getText().toString(),diagnostics,
                        low?960:1280,low?540:720,low?10:15,low?800000:1500000,
                        new SyntheticEncoder.Listener() {
                            @Override public void config(byte[] data) { }
                            @Override public void frame(Wire.Packet packet) { }
                            @Override public void preview(long frame) { main.post(()->{if(current==generation && server!=null) preview.update(frame);}); }
                            @Override public void failed() { }
                        },()->main.post(()->{if(current==generation) endSession("SOURCE_OR_NETWORK_ENDED");}));
                preview.bitmap=server.map(); watchRoute(); server.start(); message("태블릿 연결 대기 · 합성 지도만 전송합니다");
            } catch(Exception exception) { endSession("START_FAILED"); message("기기 확인 코드와 Wi-Fi를 확인하세요"); }
        });
        button("비트레이트 절반으로 낮추기",()->{ if(server!=null) server.lowerBitrate(); });
        button("시험 중지",()->endSession("STOPPED"));
        button("진단 저장",this::saveDiagnostics);
        preview=new Preview(); controls.addView(preview,new LinearLayout.LayoutParams(-1,260));
        ScrollView scroll=new ScrollView(this); scroll.addView(controls); setContentView(scroll);
    }
    @Override protected LocalRoute selectRoute() throws Exception {
        if(routeMode.getSelectedItemPosition()==1) {
            diagnostics.event("ROUTE_COMMON_AP"); return WifiRoute.select(this);
        }
        if(hotspot==null) throw new IOException("Hotspot observer unavailable");
        LocalRoute selected=hotspot.select(); diagnostics.event("ROUTE_HOTSPOT_AP"); return selected;
    }
    @Override protected String preparationFailureMessage() {
        return routeMode.getSelectedItemPosition()==1 ? "같은 Wi-Fi 연결과 기기 확인 준비를 확인하세요"
                : "핫스팟 준비 실패 · Android 16 이상에서 핫스팟을 켠 뒤 잠시 후 다시 준비하세요";
    }
    @Override protected void onTransportInvalidated(LocalRoute invalid) {
        diagnostics.event(invalid.hotspot()?"AP_ROUTE_INVALIDATED_TERMINAL":"WIFI_ROUTE_INVALIDATED_TERMINAL");
        StreamServer active=server;
        if(active!=null) active.invalidateRoute(invalid);
    }
    @Override protected void onStart() {
        super.onStart();
        if(Build.VERSION.SDK_INT>=36) {
            try {hotspot=HotspotMonitor.start(this);}
            catch(IOException exception) {hotspot=null;diagnostics.event("AP_OBSERVER_UNAVAILABLE");}
        }
    }
    @Override protected void onStop() {
        super.onStop();
        if(hotspot!=null) {hotspot.close();hotspot=null;}
    }
    @Override protected void onTick() {
        if(server!=null) status.setText("합성 시험 중 · 생성 프레임 "+diagnostics.frames+" · 인증 연결 "+diagnostics.connections);
    }
    @Override protected void endSession(String reason) {
        generation++; diagnostics.event(reason);
        if(server!=null) {server.close(); server=null;}
        releaseIdentity();
        if(peer!=null) peer.setText("");
        if(preview!=null) {preview.bitmap=null; preview.invalidate();}
        message("시험 종료 · 다시 시작하려면 기기 확인을 준비하세요");
    }
    private final class Preview extends View {
        Bitmap bitmap; long frame;
        final Paint paint=new Paint();
        Preview() {super(MainActivity.this);}
        void update(long frame) {this.frame=frame; invalidate();}
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if(bitmap==null) return;
            float scale=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
            canvas.save(); canvas.translate((getWidth()-bitmap.getWidth()*scale)/2,(getHeight()-bitmap.getHeight()*scale)/2);
            canvas.scale(scale,scale); canvas.drawBitmap(bitmap,0,0,paint);
            for(int bit=0;bit<24;bit++) {
                paint.setColor((frame&(1L<<bit))!=0?Color.WHITE:Color.BLACK);
                canvas.drawRect(bit*bitmap.getWidth()/24f,bitmap.getHeight()*11/12f,
                        (bit+1)*bitmap.getWidth()/24f,bitmap.getHeight(),paint);
            }
            canvas.restore();
        }
    }
}
