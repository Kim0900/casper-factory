package kr.magi.gnsskeeper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-selected ZIP save via Android's document picker; no storage/network permissions. */
public final class LogExportActivity extends Activity {
    private static final int SAVE_ZIP = 200;
    private ExportTask task;
    private TextView status;
    private ListView list;
    private CheckBox all;
    private Button save;
    private ProgressBar progress;
    private boolean installedList, resumed;
    private static boolean idle() {
        return "STOPPED".equals(GnssSnapshot.phase) && !GnssSnapshot.running && !GnssSnapshot.observerRegistered;
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density); layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("로그 내보내기"); title.setTextSize(24); layout.addView(title);
        TextView description = new TextView(this);
        description.setText("기록을 중지한 뒤 세션을 선택하세요. CSV와 세션 정보를 ZIP으로 저장합니다.\n위치·시각 정보가 포함되며 ZIP 자체는 암호화되지 않습니다. 저장 위치와 공유 대상을 확인하세요.");
        layout.addView(description);
        status = new TextView(this); status.setId(R.id.exportStatus); status.setPadding(0, pad / 2, 0, pad / 2); layout.addView(status);
        progress = new ProgressBar(this); layout.addView(progress);
        all = new CheckBox(this); all.setId(R.id.exportSelectAll); all.setText("전체 선택"); layout.addView(all);
        list = new ListView(this); list.setId(R.id.exportSessions); list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        save = new Button(this); save.setId(R.id.exportSave); save.setText("선택한 기록 내보내기"); layout.addView(save);
        Button close = new Button(this); close.setText("닫기"); layout.addView(close);
        setContentView(layout);
        Object retained = getLastNonConfigurationInstance();
        task = retained instanceof ExportTask ? (ExportTask) retained : new ExportTask(getApplicationContext(), saved);
        all.setOnCheckedChangeListener((button, checked) -> {
            if (!"READY".equals(task.phase)) return;
            for (int i = 0; i < task.selected.length; i++) { task.selected[i] = checked; list.setItemChecked(i, checked); }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if ("READY".equals(task.phase)) task.selected[position] = list.isItemChecked(position);
        });
        save.setOnClickListener(v -> {
            if (!idle()) { task.message = "먼저 메인 화면에서 ‘중지’를 눌러 기록 종료를 확인하세요."; render(); return; }
            task.prepare();
        });
        close.setOnClickListener(v -> finish());
        task.listener = this::render;
        if (retained == null && "NEW".equals(task.phase)) {
            if (idle()) task.scan(); else task.set("ERROR", "먼저 메인 화면에서 ‘중지’를 누르고 기록 종료가 끝난 뒤 다시 열어 주세요.");
        }
        render();
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        boolean ready = "READY".equals(task.phase);
        status.setText(task.message);
        progress.setVisibility("SCANNING".equals(task.phase) || "PREPARING".equals(task.phase) || "WRITING".equals(task.phase) ? View.VISIBLE : View.GONE);
        all.setEnabled(ready); list.setEnabled(ready); save.setEnabled(ready && !task.sessions.isEmpty());
        if (!installedList && !task.sessions.isEmpty()) {
            List<String> labels = new ArrayList<>(); for (LogExporter.Session s : task.sessions) labels.add(s.label());
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
            for (int i = 0; i < task.selected.length; i++) list.setItemChecked(i, task.selected[i]);
            installedList = true;
        }
        if ("CHOOSE_DESTINATION".equals(task.phase) && !task.pickerLaunched && resumed) {
            task.pickerLaunched = true;
            String name = "GNSSKeeper_logs_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + ".zip";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { startActivityForResult(intent, SAVE_ZIP); }
            catch (RuntimeException ex) { task.discard("저장 화면을 열 수 없습니다. 다른 파일 저장 앱이 있는지 확인해 주세요."); }
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_ZIP) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            task.discard("저장을 취소했습니다. 원본 기록은 그대로 유지됩니다."); return;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { task.discard("지원하지 않는 저장 위치입니다. 다른 위치를 선택해 주세요."); return; }
        task.write(uri);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; render(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override public Object onRetainNonConfigurationInstance() { return task; }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("export_phase", task.phase);
        if (task.archive != null) {
            out.putString("archive_name", task.archive.file.getName()); out.putString("archive_sha", task.archive.sha256);
            out.putInt("archive_sessions", task.archive.sessions); out.putBoolean("picker_launched", task.pickerLaunched);
        }
    }
    @Override protected void onDestroy() {
        if (task != null) { task.listener = null;
            if (!isChangingConfigurations()) task.close(); }
        super.onDestroy();
    }

    /** Survives configuration changes; owns only application context and worker state. */
    private static final class ExportTask {
        final Context app;
        final Handler main = new Handler(Looper.getMainLooper());
        final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "GNSSKeeper-export"); t.setDaemon(true); return t; });
        final AtomicBoolean cancelled = new AtomicBoolean();
        final File cache;
        List<LogExporter.Session> sessions = new ArrayList<>();
        boolean[] selected = new boolean[0];
        String phase = "NEW", message = "기록을 확인하고 있습니다.";
        boolean pickerLaunched;
        volatile LogExporter.Archive archive;
        Runnable listener;
        ExportTask(Context app, Bundle saved) {
            this.app = app; cache = new File(app.getCacheDir(), "log_exports");
            if (saved == null) return;
            String oldPhase = saved.getString("export_phase", "");
            String name = saved.getString("archive_name", ""), hash = saved.getString("archive_sha", "");
            if ("CHOOSE_DESTINATION".equals(oldPhase) && name.matches("gnss_export_[A-Za-z0-9_-]+\\.zip") && hash.matches("[0-9a-f]{64}")) {
                archive = new LogExporter.Archive(new File(cache, name), hash, saved.getInt("archive_sessions", 0));
                pickerLaunched = saved.getBoolean("picker_launched"); phase = "CHOOSE_DESTINATION"; message = "저장 위치를 선택해 주세요.";
            } else if ("WRITING".equals(oldPhase)) {
                phase = "ERROR"; message = "이전 저장 작업이 중단되었습니다. 저장 위치에 불완전한 ZIP이 있을 수 있습니다. 원본 기록은 유지됩니다. 다시 열어 내보내 주세요.";
                worker.execute(this::clearCache);
            }
        }
        void set(String phase, String message) {
            if (cancelled.get()) return;
            this.phase = phase; this.message = message;
            if (listener != null) listener.run();
        }
        void scan() {
            set("SCANNING", "저장된 기록을 확인하고 있습니다.");
            worker.execute(() -> {
                clearCache();
                try {
                    List<LogExporter.Session> found = LogExporter.listSessions(app.getExternalFilesDir("gnss_logs"), new File(app.getFilesDir(), "gnss_logs"));
                    main.post(() -> {
                        if (cancelled.get()) return;
                        sessions = found; selected = new boolean[found.size()];
                        set("READY", found.isEmpty() ? "저장된 로그가 없습니다. 기록을 시작한 뒤 중지하고 다시 열어 주세요." : found.size() + "개 세션이 있습니다. 내보낼 기록을 선택하세요.");
                    });
                } catch (IOException | RuntimeException ex) { main.post(() -> set("ERROR", "기록 목록을 읽을 수 없습니다. 저장소 상태를 확인해 주세요.")); }
            });
        }
        void prepare() {
            if (!"READY".equals(phase)) return;
            List<LogExporter.Session> chosen = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(sessions.get(i));
            if (chosen.isEmpty()) { set("READY", "내보낼 세션을 하나 이상 선택하세요."); return; }
            set("PREPARING", "선택한 기록을 묶고 파일 정합성을 확인하고 있습니다.");
            worker.execute(() -> {
                try {
                    LogExporter.Archive prepared = LogExporter.prepare(chosen, cache, BuildConfig.VERSION_NAME, cancelled);
                    archive = prepared;
                    main.post(() -> {
                        if (cancelled.get()) return;
                        pickerLaunched = false; set("CHOOSE_DESTINATION", "저장 위치를 선택해 주세요.");
                    });
                } catch (IOException | RuntimeException ex) { main.post(() -> set("READY", "ZIP 준비에 실패했습니다. 저장 공간을 확인한 뒤 다시 시도하세요. 원본 기록은 유지됩니다.")); }
            });
        }
        void write(Uri uri) {
            if (!"CHOOSE_DESTINATION".equals(phase) || archive == null) return;
            LogExporter.Archive current = archive;
            set("WRITING", "ZIP을 저장하고 저장된 파일의 정합성을 확인하고 있습니다.");
            worker.execute(() -> {
                try {
                    LogExporter.save(current, new LogExporter.Destination() {
                        @Override public OutputStream openWrite() throws IOException { return app.getContentResolver().openOutputStream(uri, "wt"); }
                        @Override public InputStream openRead() throws IOException { return app.getContentResolver().openInputStream(uri); }
                    }, cancelled);
                    main.post(() -> set("DONE", current.sessions + "개 세션을 ZIP으로 저장하고 정합성을 확인했습니다. 선택한 저장 위치에서 파일을 찾아 첨부하거나 공유하시면 됩니다. 원본 기록도 유지됩니다."));
                } catch (IOException | RuntimeException ex) {
                    main.post(() -> set("ERROR", "저장 또는 저장 파일 확인에 실패했습니다. 대상 위치에 불완전한 ZIP이 남았을 수 있습니다. 다른 위치로 다시 내보내 주세요. 원본 기록은 유지됩니다."));
                } finally { delete(current); }
            });
        }
        void discard(String text) {
            LogExporter.Archive current = archive; archive = null; pickerLaunched = false;
            worker.execute(() -> {
                delete(current);
                main.post(() -> set(sessions.isEmpty() ? "ERROR" : "READY", text));
            });
        }
        void clearCache() {
            File[] files = cache.listFiles(); if (files == null) return;
            for (File file : files) if (file.getName().startsWith("gnss_export_") && file.getName().endsWith(".zip"))
                try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
        }
        void delete(LogExporter.Archive a) { if (a != null) try { Files.deleteIfExists(a.file.toPath()); } catch (IOException ignored) { } }
        void close() {
            cancelled.set(true);
            // This runs after any in-flight prepare/write; no main-thread I/O or waiting.
            worker.execute(() -> delete(archive)); worker.shutdown();
        }
    }
}
