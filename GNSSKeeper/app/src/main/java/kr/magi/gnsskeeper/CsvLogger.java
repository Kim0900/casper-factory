package kr.magi.gnsskeeper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Worker-owned files. Each new session is exclusive; previous sessions are never appended. */
final class CsvLogger implements Closeable {
    interface WriterFactory { Writer open(File file) throws IOException; }
    static final WriterFactory FILES = file -> {
        if (!file.createNewFile()) throw new IOException("File exists");
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    };
    final File sessionDir;
    final String sessionId;
    private final String mode;
    private final long epoch, startNanos;
    private final Map<String, String> metadata;
    private final List<Writer> opened = new ArrayList<>();
    private Writer observer, direct, status, measurements, events;
    private long lastReceipt, lastFix = -1, samples;
    private String lastProvider = "";
    private boolean closed;
    CsvLogger(File base, String mode, long epoch, long startNanos, Map<String, String> metadata) throws IOException {
        this(base, mode, epoch, startNanos, metadata, FILES);
    }
    CsvLogger(File base, String mode, long epoch, long startNanos, Map<String, String> metadata,
            WriterFactory factory) throws IOException {
        this.mode = mode; this.epoch = epoch; this.startNanos = startNanos;
        this.metadata = new LinkedHashMap<>(metadata);
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("No session root");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File created = null;
        for (int n = 0; n < 5; n++) {
            File candidate = new File(base, mode + "_" + stamp + "_" + UUID.randomUUID());
            if (candidate.mkdir()) { created = candidate; break; }
        }
        if (created == null) throw new IOException("Cannot create exclusive session");
        sessionDir = created; sessionId = created.getName();
        try {
            observer = open(factory, "observer_passive.csv", "wall_time_ms,fix_elapsed_nanos,received_elapsed_nanos,sequence,experiment_id,session_id,condition_epoch,mode,observer_provider,source_provider,latitude,longitude,accuracy_m,speed_mps,bearing_deg,altitude_m,fix_age_ms,gap_from_prev_ms,within_condition,duplicate_fix,is_mock");
            if ("ACTIVE".equals(mode)) direct = open(factory, "diagnostic_gps.csv", "wall_time_ms,fix_elapsed_nanos,received_elapsed_nanos,source_provider,accuracy_m,fix_age_ms");
            status = open(factory, "gnss_status.csv", "wall_time_ms,received_elapsed_nanos,signal_entries,used_entries,l1_like_entries,l5_like_entries,max_cn0_dbhz,avg_cn0_dbhz");
            measurements = open(factory, "gnss_measurements.csv", "wall_time_ms,received_elapsed_nanos,event_index,clock_discontinuity,measurement_count,l1_like_entries,l5_like_entries,avg_cn0_dbhz");
            events = open(factory, "events.csv", "wall_time_ms,elapsed_nanos,event,detail");
            event(System.currentTimeMillis(), startNanos, "START", mode);
            writeMetadata(0, "OPEN", "");
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private Writer open(WriterFactory factory, String name, String header) throws IOException {
        Writer w = factory.open(new File(sessionDir, name));
        opened.add(w); // Register before header write/flush so partial construction is closeable.
        row(w, header);
        return w;
    }
    private static String cell(String value) {
        return value == null ? "" : value.replace(',', '_').replace('\n', '_').replace('\r', '_').replace('"', '_');
    }
    private void row(Writer writer, String value) throws IOException {
        if (closed) throw new IOException("Session closed");
        writer.write(value + "\n"); writer.flush();
    }
    void logObserver(LocationSample s) throws IOException {
        long gap = lastReceipt == 0 ? -1 : (s.receivedNanos - lastReceipt) / 1_000_000L;
        boolean duplicate = s.fixNanos == lastFix && s.sourceProvider.equals(lastProvider);
        row(observer, String.format(Locale.ROOT,
                "%d,%d,%d,%d,%s,%s,%d,%s,passive,%s,%.8f,%.8f,%s,%s,%s,%s,%d,%d,%s,%s,%s",
                s.wallMs, s.fixNanos, s.receivedNanos, s.sequence, cell(metadata.get("experiment_id")),
                sessionId, epoch, mode, cell(s.sourceProvider), s.latitude, s.longitude,
                s.accuracy, s.speed, s.bearing, s.altitude, s.ageMs(), gap, s.within(startNanos), duplicate, s.mock));
        samples++; lastReceipt = s.receivedNanos; lastFix = s.fixNanos; lastProvider = s.sourceProvider;
    }
    void logDirect(LocationSample s) throws IOException {
        if (direct != null) row(direct, s.wallMs + "," + s.fixNanos + "," + s.receivedNanos + "," + cell(s.sourceProvider) + "," + s.accuracy + "," + s.ageMs());
    }
    void logStatus(long wall, long receipt, int visible, int used, int l1, int l5, float max, float avg) throws IOException {
        row(status, wall + "," + receipt + "," + visible + "," + used + "," + l1 + "," + l5 + "," + max + "," + avg);
    }
    void logMeasurement(long wall, long receipt, long idx, int discontinuity, int count, int l1, int l5, float avg) throws IOException {
        row(measurements, wall + "," + receipt + "," + idx + "," + discontinuity + "," + count + "," + l1 + "," + l5 + "," + avg);
    }
    void event(long wall, long elapsed, String event, String detail) throws IOException {
        row(events, wall + "," + elapsed + "," + cell(event) + "," + cell(detail));
    }
    private static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private void writeMetadata(long end, String state, String reason) throws IOException {
        Map<String, String> values = new LinkedHashMap<>(metadata);
        values.put("schema", "2"); values.put("session_id", sessionId); values.put("condition_id", mode);
        values.put("condition_epoch", Long.toString(epoch)); values.put("observer_provider", "passive");
        values.put("start_elapsed_nanos", Long.toString(startNanos)); values.put("end_elapsed_nanos", Long.toString(end));
        values.put("observer_samples", Long.toString(samples)); values.put("state", state); values.put("end_reason", reason);
        StringBuilder json = new StringBuilder("{\n");
        for (Map.Entry<String, String> v : values.entrySet()) {
            if (json.length() > 2) json.append(",\n");
            json.append("  ").append(json(v.getKey())).append(": ").append(json(v.getValue()));
        }
        json.append("\n}\n");
        File temp = new File(sessionDir, "session.json.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) { w.write(json.toString()); }
        if (!temp.renameTo(new File(sessionDir, "session.json"))) throw new IOException("Metadata commit failed");
    }
    void finish(long endNanos, String reason) throws IOException {
        IOException failure = null;
        try { event(System.currentTimeMillis(), endNanos, "END", reason); }
        catch (IOException ex) { failure = ex; }
        try { close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        try { writeMetadata(endNanos, failure == null ? "CLOSED" : "INCOMPLETE", reason); }
        catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        if (failure != null) throw failure;
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Writer w : opened) {
            try { w.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        }
        opened.clear();
        if (failure != null) throw failure;
    }
}
