package kr.magi.mirror.common;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public abstract class LabActivity extends Activity {
    protected final Handler main = new Handler(Looper.getMainLooper());
    protected Diagnostics diagnostics = new Diagnostics();
    protected LinearLayout controls;
    protected TextView status;
    protected String role;
    protected TlsIdentity identity;
    protected volatile LocalRoute route;
    protected volatile boolean visible;
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (getSystemService(KeyguardManager.class).isDeviceLocked()) endSession("LOCKED");
            if (diagnostics.sample(LabActivity.this)) endSession("THERMAL_SEVERE");
            onTick();
            main.postDelayed(this, 250);
        }
    };

    protected void createControls(String role) {
        this.role = role;
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(12, 8, 12, 8);
        status = text("합성 시험용 · 기기 확인 코드를 먼저 교환하세요");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    protected TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(14); controls.addView(view);
        return view;
    }
    protected EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint); view.setSingleLine(true); view.setTextSize(14);
        view.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        controls.addView(view); return view;
    }
    protected Button button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title);
        button.setOnClickListener(view -> action.run()); controls.addView(button); return button;
    }
    protected void message(String message) { main.post(() -> status.setText(message)); }

    protected void prepare(TextView fingerprint, TextView address) {
        endSession("PREPARE");
        try {
            route = selectRoute();
            identity = new TlsIdentity();
            fingerprint.setText(identity.fingerprint());
            address.setText(route.label()+"  " + route.address.getHostAddress());
            message("상대 기기에 위 64자리 확인 코드를 입력하세요");
        } catch (Exception exception) { endSession("PREPARE_FAILED"); message(preparationFailureMessage()); }
    }

    protected LocalRoute selectRoute() throws Exception { return WifiRoute.select(this); }
    protected String preparationFailureMessage() { return "Wi-Fi 또는 기기 확인 준비 실패"; }
    protected void onTransportInvalidated(LocalRoute invalid) { }
    protected void watchRoute() {
        LocalRoute selected=route;
        selected.watch(() -> {
            onTransportInvalidated(selected);
            main.post(() -> {if(route==selected) endSession("NETWORK_CHANGED");});
        });
    }
    protected void saveDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "geumbok-"+role+"-diagnostics.txt");
        startActivityForResult(intent, 71);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 71 && result == RESULT_OK && data != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output != null) output.write(diagnostics.export(role).getBytes(StandardCharsets.UTF_8));
            } catch (Exception exception) { message("진단 저장 실패"); }
        }
    }
    @Override protected void onStart() { super.onStart(); visible = true; main.post(monitor); }
    @Override protected void onStop() {
        visible = false; main.removeCallbacks(monitor); endSession("NOT_VISIBLE"); super.onStop();
    }
    protected void onTick() { }
    protected final void releaseIdentity() {
        if (route != null) { route.close(); route = null; }
        if (identity != null) { identity.close(); identity = null; }
    }
    protected abstract void endSession(String reason);
}
