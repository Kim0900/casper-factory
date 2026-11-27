package kr.magi.gnsskeeper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Bounded-memory, read-only source export. All methods run on the export worker. */
final class LogExporter {
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList("observer_passive.csv", "diagnostic_gps.csv",
            "gnss_status.csv", "gnss_measurements.csv", "events.csv", "session.json", "session.json.tmp"));
    private static final Pattern SESSION = Pattern.compile("(?:PASSIVE|ACTIVE)_[0-9A-Za-z_-]+");
    private static final Pattern STATE = Pattern.compile("\"state\"\\s*:\\s*\"(CLOSED|OPEN|INCOMPLETE)\"");
    static final class Session {
        final File root, directory;
        final String storage, id, key;
        final int fileCount;
        Session(File root, File directory, String storage, int count) {
            this.root = root; this.directory = directory; this.storage = storage;
            id = directory.getName(); key = storage + "/" + id; fileCount = count;
        }
        String label() {
            String[] parts = id.split("_");
            String time = parts.length >= 3 ? parts[1] + " " + parts[2] : id;
            return parts[0] + " · " + time + " · " + fileCount + "개 파일" + ("internal".equals(storage) ? " · 내부 저장소" : "");
        }
    }
    static final class Archive {
        final File file;
        final String sha256;
        final int sessions;
        Archive(File file, String sha256, int sessions) { this.file = file; this.sha256 = sha256; this.sessions = sessions; }
    }
    interface Destination {
        OutputStream openWrite() throws IOException;
        InputStream openRead() throws IOException;
    }
    static final class Cancelled extends IOException { Cancelled() { super("Export cancelled"); } }
    private static void check(AtomicBoolean cancelled) throws Cancelled {
        if (cancelled.get()) throw new Cancelled();
    }
    static List<Session> listSessions(File external, File internal) throws IOException {
        List<Session> result = new ArrayList<>();
        addSessions(result, external, "external"); addSessions(result, internal, "internal");
        result.sort(Comparator.comparing((Session s) -> s.id.substring(s.id.indexOf('_') + 1)).reversed().thenComparing(s -> s.storage));
        return result;
    }
    private static void addSessions(List<Session> out, File root, String storage) throws IOException {
        if (root == null || !root.exists()) return;
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("Linked source root");
        File canonical = root.getCanonicalFile();
        File[] children = canonical.listFiles();
        if (children == null) throw new IOException("Cannot list source root");
        for (File dir : children) {
            if (!SESSION.matcher(dir.getName()).matches() || Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory()) continue;
            if (!canonical.equals(dir.getCanonicalFile().getParentFile())) throw new IOException("Invalid session boundary");
            List<File> files = sessionFiles(canonical, dir);
            if (!files.isEmpty()) out.add(new Session(canonical, dir.getCanonicalFile(), storage, files.size()));
        }
    }
    private static List<File> sessionFiles(File root, File dir) throws IOException {
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(dir.toPath()) || !dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("Invalid session path");
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("Cannot list session");
        List<File> files = new ArrayList<>();
        for (File file : children) {
            String name = file.getName();
            if (!NAMES.contains(name) && !name.equals(dir.getName() + ".csv")) continue;
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || !dir.getCanonicalFile().equals(file.getCanonicalFile().getParentFile()))
                throw new IOException("Unsafe export source");
            files.add(file);
        }
        files.sort(Comparator.comparing(File::getName));
        return files;
    }
    static Archive prepare(List<Session> selected, File cache, String version, AtomicBoolean cancelled) throws IOException {
        if (selected.isEmpty()) throw new IOException("No sessions selected");
        if (Files.isSymbolicLink(cache.toPath()) || (!cache.isDirectory() && !cache.mkdirs())) throw new IOException("No private cache");
        File output = File.createTempFile("gnss_export_", ".zip", cache);
        boolean success = false;
        Map<String, String> hashes = new LinkedHashMap<>();
        StringBuilder manifest = new StringBuilder("{\n  \"format_version\":1,\n  \"app_version\":").append(json(version))
                .append(",\n  \"created_wall_ms\":").append(System.currentTimeMillis())
                .append(",\n  \"contains_location_data\":true,\n  \"encrypted\":false,\n  \"sessions\":[");
        Set<String> sessionKeys = new HashSet<>();
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
                boolean firstSession = true;
                for (Session session : selected) {
                    check(cancelled);
                    if (!sessionKeys.add(session.key) || !SESSION.matcher(session.id).matches()
                            || !("external".equals(session.storage) || "internal".equals(session.storage))) throw new IOException("Invalid session selection");
                    List<File> files = sessionFiles(session.root, session.directory);
                    if (files.isEmpty()) throw new IOException("Session disappeared");
                    if (!firstSession) manifest.append(','); firstSession = false;
                    manifest.append("\n    {\"id\":").append(json(session.id)).append(",\"storage\":").append(json(session.storage))
                            .append(",\"original_metadata_state\":").append(json(metadataState(session.directory))).append(",\"files\":[");
                    boolean firstFile = true;
                    for (File source : files) {
                        check(cancelled);
                        long size = source.length(), modified = source.lastModified();
                        String name = session.key + "/" + source.getName();
                        ZipEntry entry = new ZipEntry(name); entry.setTime(modified); zip.putNextEntry(entry);
                        String hash;
                        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
                            MessageDigest digest = sha(); long copied = copy(input, zip, digest, cancelled);
                            if (copied != size || source.length() != size || source.lastModified() != modified)
                                throw new IOException("Source changed during export");
                            hash = hex(digest.digest());
                        }
                        zip.closeEntry(); hashes.put(name, hash);
                        if (!firstFile) manifest.append(','); firstFile = false;
                        manifest.append("{\"path\":").append(json(name)).append(",\"bytes\":").append(size)
                                .append(",\"sha256\":").append(json(hash)).append('}');
                    }
                    manifest.append("]}");
                }
                manifest.append("\n  ]\n}\n");
                byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry("export_manifest.json")); zip.write(bytes); zip.closeEntry();
                hashes.put("export_manifest.json", hex(sha().digest(bytes)));
            }
            check(cancelled);
            verifyZip(output, hashes, cancelled);
            String hash = digest(output, cancelled);
            success = true;
            return new Archive(output, hash, selected.size());
        } finally { if (!success) Files.deleteIfExists(output.toPath()); }
    }
    private static String metadataState(File dir) throws IOException {
        File metadata = new File(dir, "session.json");
        if (!metadata.exists()) return "MISSING";
        if (metadata.length() > 1_048_576) return "UNKNOWN";
        Matcher matcher = STATE.matcher(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
        return matcher.find() ? matcher.group(1) : "UNKNOWN";
    }
    private static void verifyZip(File file, Map<String, String> hashes, AtomicBoolean cancelled) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() != hashes.size()) throw new IOException("Archive entry count mismatch");
            for (Map.Entry<String, String> expected : hashes.entrySet()) {
                check(cancelled); ZipEntry entry = zip.getEntry(expected.getKey());
                if (entry == null) throw new IOException("Missing archive entry");
                try (InputStream input = zip.getInputStream(entry)) {
                    if (!expected.getValue().equals(digest(input, cancelled))) throw new IOException("Archive integrity mismatch");
                }
            }
        }
    }
    static void save(Archive archive, Destination destination, AtomicBoolean cancelled) throws IOException {
        check(cancelled);
        if (!archive.sha256.matches("[0-9a-f]{64}") || !archive.sha256.equals(digest(archive.file, cancelled))) throw new IOException("Prepared archive changed");
        try (InputStream input = new BufferedInputStream(new FileInputStream(archive.file));
                OutputStream output = destination.openWrite()) {
            if (output == null) throw new IOException("No output stream");
            copy(input, output, null, cancelled); output.flush();
        } // A close/commit failure must propagate before any success state.
        check(cancelled);
        try (InputStream input = destination.openRead()) {
            if (input == null || !archive.sha256.equals(digest(input, cancelled))) throw new IOException("Saved archive verification failed");
        }
        check(cancelled);
    }
    static String digest(File file, AtomicBoolean cancelled) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) { return digest(input, cancelled); }
    }
    private static String digest(InputStream input, AtomicBoolean cancelled) throws IOException {
        MessageDigest hash = sha(); copy(input, null, hash, cancelled); return hex(hash.digest());
    }
    private static long copy(InputStream input, OutputStream output, MessageDigest digest, AtomicBoolean cancelled) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int read;
        while ((read = input.read(buffer)) != -1) {
            check(cancelled);
            if (output != null) output.write(buffer, 0, read);
            if (digest != null) digest.update(buffer, 0, read);
            total += read;
        }
        return total;
    }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); } }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
