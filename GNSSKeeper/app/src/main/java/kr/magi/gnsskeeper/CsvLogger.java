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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Worker-owned files. Each new session is exclusive; previous sessions are never appended.
 * v0.3: schema 3 (columns only appended at file ends, new constellation file), 20 MB hard cap (§18-0).
 */
final class CsvLogger implements Closeable {
    static final String SCHEMA_VERSION = "3";
    /** Terminal/recovery events may use the reserved 64 KB; everything else is a summary row. */
    static final Set<String> TERMINAL_EVENTS = new HashSet<>(Arrays.asList("END", "LOG_CAP", "RECOVERY_GAP",
            "RESTART_HALTED", "RESTART_BLOCKED", "USER_STOP", "USER_STOP_EXTERNAL", "AUTO_RESTART", "PREV_EXIT",
            "FIRST_FIX", "SERVICE_STARTED", "FGS_STARTED", "LOCATION_REGISTERED", "RECOVERY_STATE_COMMIT_FAIL"));
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
    private Writer observer, direct, status, measurements, events, constellation;
    final LogBudget budget;
    private boolean capNoted;
    private long lastReceipt, lastFix = -1, samples;
    private String lastProvider = "";
    private boolean closed;
    CsvLogger(File base, String mode, long epoch, long startNanos, Map<String, String> metadata) throws IOException {
        this(base, mode, epoch, startNanos, metadata, FILES, new LogBudget());
    }
    CsvLogger(File base, String mode, long epoch, long startNanos, Map<String, String> metadata,
            WriterFactory factory) throws IOException {
        this(base, mode, epoch, startNanos, metadata, factory, new LogBudget());
    }
    CsvLogger(File base, String mode, long epoch, long startNanos, Map<String, String> metadata,
            WriterFactory factory, LogBudget budget) throws IOException {
        this.budget = budget;
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
            measurements = open(factory, "gnss_measurements.csv", "wall_time_ms,received_elapsed_nanos,event_index,clock_discontinuity,measurement_count,l1_like_entries,l5_like_entries,avg_cn0_dbhz,adr_valid_count,multipath_detected_count,clock_interval_ms");
            events = open(factory, "events.csv", "wall_time_ms,elapsed_nanos,event,detail");
            if ("ACTIVE".equals(mode)) constellation = open(factory, "gnss_constellation.csv", ConstellationSummary.HEADER);
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
        if (!budget.tryTerminal(bytes(header))) throw new IOException("Log cap reached before header");
        row(w, header);
        return w;
    }
    private static String cell(String value) {
        return value == null ? "" : value.replace(',', '_').replace('\n', '_').replace('\r', '_').replace('"', '_');
    }
    private static long bytes(String row) { return row.getBytes(StandardCharsets.UTF_8).length + 1L; }
    private void row(Writer writer, String value) throws IOException {
        if (closed) throw new IOException("Session closed");
        writer.write(value + "\n"); writer.flush();
    }
    /** Summary row under the cap; returns false (no error) once capped and records LOG_CAP once. */
    private boolean summary(Writer writer, String value) throws IOException {
        if (closed) throw new IOException("Session closed");
        if (budget.trySummary(bytes(value))) { row(writer, value); return true; }
        if (!capNoted) {
            capNoted = true;
            terminal(events, System.currentTimeMillis() + ",0,LOG_CAP," + cell("cap_bytes=" + LogBudget.HARD_CAP_BYTES
                    + ";used=" + budget.total() + ";summary_stopped=1;location_service=continues"));
        }
        return false;
    }
    private boolean terminal(Writer writer, String value) throws IOException {
        if (closed) throw new IOException("Session closed");
        if (!budget.tryTerminal(bytes(value))) return false; // never exceed the hard cap
        row(writer, value); return true;
    }
    boolean capped() { return budget.capped(); }
    void logObserver(LocationSample s) throws IOException {
        long gap = lastReceipt == 0 ? -1 : (s.receivedNanos - lastReceipt) / 1_000_000L;
        boolean duplicate = s.fixNanos == lastFix && s.sourceProvider.equals(lastProvider);
        if (!summary(observer, String.format(Locale.ROOT,
                "%d,%d,%d,%d,%s,%s,%d,%s,passive,%s,%.8f,%.8f,%s,%s,%s,%s,%d,%d,%s,%s,%s",
                s.wallMs, s.fixNanos, s.receivedNanos, s.sequence, cell(metadata.get("experiment_id")),
                sessionId, epoch, mode, cell(s.sourceProvider), s.latitude, s.longitude,
                s.accuracy, s.speed, s.bearing, s.altitude, s.ageMs(), gap, s.within(startNanos), duplicate, s.mock))) return;
        samples++; lastReceipt = s.receivedNanos; lastFix = s.fixNanos; lastProvider = s.sourceProvider;
    }
    void logDirect(LocationSample s) throws IOException {
        if (direct != null) summary(direct, s.wallMs + "," + s.fixNanos + "," + s.receivedNanos + "," + cell(s.sourceProvider) + "," + s.accuracy + "," + s.ageMs());
    }
    void logStatus(long wall, long receipt, int visible, int used, int l1, int l5, float max, float avg) throws IOException {
        summary(status, wall + "," + receipt + "," + visible + "," + used + "," + l1 + "," + l5 + "," + max + "," + avg);
    }
    void logMeasurement(long wall, long receipt, long idx, int discontinuity, int count, int l1, int l5, float avg,
            int adrValid, int multipath, long clockIntervalMs) throws IOException {
        summary(measurements, wall + "," + receipt + "," + idx + "," + discontinuity + "," + count + "," + l1 + "," + l5 + "," + avg
                + "," + adrValid + "," + multipath + "," + clockIntervalMs);
    }
    void logConstellation(long wall, long receipt, java.util.List<String> rows) throws IOException {
        if (constellation == null) return;
        for (String r : rows) if (!summary(constellation, wall + "," + receipt + "," + r)) return;
    }
    void event(long wall, long elapsed, String event, String detail) throws IOException {
        String value = wall + "," + elapsed + "," + cell(event) + "," + cell(detail);
        if (TERMINAL_EVENTS.contains(event)) terminal(events, value); else summary(events, value);
    }
    private static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private void writeMetadata(long end, String state, String reason) throws IOException {
        Map<String, String> values = new LinkedHashMap<>(metadata);
        values.put("schema", SCHEMA_VERSION); values.put("schema_version", SCHEMA_VERSION); values.put("session_id", sessionId); values.put("condition_id", mode);
        values.put("condition_epoch", Long.toString(epoch)); values.put("observer_provider", "passive");
        values.put("start_elapsed_nanos", Long.toString(startNanos)); values.put("end_elapsed_nanos", Long.toString(end));
        values.put("observer_samples", Long.toString(samples)); values.put("state", state); values.put("end_reason", reason);
        StringBuilder json = new StringBuilder("{\n");
        for (Map.Entry<String, String> v : values.entrySet()) {
            if (json.length() > 2) json.append(",\n");
            json.append("  ").append(json(v.getKey())).append(": ").append(json(v.getValue()));
        }
        json.append("\n}\n");
        if (!budget.setMetadata(json.toString().getBytes(StandardCharsets.UTF_8).length))
            throw new IOException("Log hard cap: metadata does not fit");
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
