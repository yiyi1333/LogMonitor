package com.logmonitor.agent;

import com.logmonitor.agent.AgentHttpClient.UploadException;
import com.logmonitor.agent.AgentModels.BatchMetadata;
import com.logmonitor.agent.AgentModels.FileState;
import com.logmonitor.agent.AgentModels.Heartbeat;
import com.logmonitor.agent.AgentModels.LocalConfig;
import com.logmonitor.agent.AgentModels.RemoteConfig;
import com.logmonitor.agent.AgentModels.RuntimeState;
import com.logmonitor.agent.AgentModels.SourceConfig;
import com.logmonitor.agent.AgentModels.SourceReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

final class AgentRuntime implements AutoCloseable {
    private static final long SPOOL_ENTRY_RESERVE = 64 * 1024;
    private final Path configPath;
    private final Path dataDirectory;
    private final Path spoolDirectory;
    private final Path statePath;
    private final Path remoteConfigPath;
    private final LocalConfig local;
    private final AgentHttpClient http;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3);
    private final Map<Long, SourceReport> reports = new HashMap<Long, SourceReport>();
    private RuntimeState state;
    private RemoteConfig remote;

    AgentRuntime(Path configPath, Path dataDirectory) throws IOException {
        this.configPath = configPath;
        this.dataDirectory = dataDirectory;
        this.spoolDirectory = dataDirectory.resolve("spool");
        this.statePath = dataDirectory.resolve("state.json");
        this.remoteConfigPath = dataDirectory.resolve("sources.json");
        this.local = AgentFiles.read(configPath, LocalConfig.class);
        this.http = new AgentHttpClient(local.serverUrl, local.token, local.allowHttp);
        Files.createDirectories(spoolDirectory);
        this.state = Files.exists(statePath) ? AgentFiles.read(statePath, RuntimeState.class) : new RuntimeState();
        this.remote = Files.exists(remoteConfigPath) ? AgentFiles.read(remoteConfigPath, RemoteConfig.class) : new RemoteConfig();
        removeOrphanPayloads();
        recoverQueuedOffsets();
    }

    void run() throws InterruptedException {
        executor.scheduleWithFixedDelay(guard("config", new Task() { public void run() throws Exception { pollConfiguration(); }}), 0, 10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(guard("scan", new Task() { public void run() throws Exception { scan(); }}), 1, 10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(guard("upload", new Task() { public void run() throws Exception { uploadOne(); }}), 2, 1, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(guard("heartbeat", new Task() { public void run() throws Exception { heartbeat(); }}), 3, 30, TimeUnit.SECONDS);
        new CountDownLatch(1).await();
    }

    synchronized void runOnce() throws Exception {
        pollConfiguration();
        scan();
        while (uploadOne()) {}
        heartbeat();
    }

    private synchronized void pollConfiguration() throws IOException {
        RemoteConfig update = http.configuration(remote == null ? 0 : remote.revision);
        if (update == null) return;
        Set<Long> active = new HashSet<Long>();
        for (SourceConfig source : update.sources) active.add(source.id);
        clearRemovedSources(active);
        remote = update;
        local.configRevision = update.revision;
        AgentFiles.writeAtomic(remoteConfigPath, remote);
        AgentFiles.writeAtomic(configPath, local);
        AgentFiles.ownerOnly(configPath);
    }

    private synchronized void scan() throws Exception {
        if (remote == null || remote.sources == null) return;
        long available = local.spoolLimitBytes - spoolBytes();
        for (SourceConfig source : remote.sources) {
            SourceReport report = validate(source);
            reports.put(source.id, report);
            if (!"ACTIVE".equals(report.status) || available <= SPOOL_ENTRY_RESERVE) {
                if (available <= SPOOL_ENTRY_RESERVE) report.status = "BLOCKED";
                continue;
            }
            List<Path> files = matchingFiles(source);
            report.files = files.size();
            for (Path file : files) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                report.totalBytes += attributes.size();
                String fileKey = fileKey(file, attributes);
                String key = source.id + "|" + fileKey;
                FileState fileState = state.files.get(key);
                if (fileState == null) {
                    fileState = new FileState();
                    fileState.sourceId = source.id;
                    fileState.fileKey = fileKey;
                    fileState.generation = UUID.randomUUID().toString();
                    fileState.path = file.toString();
                    fileState.offset = "NOW".equals(source.startMode) ? attributes.size() : 0;
                    fileState.lastSize = attributes.size();
                    fileState.stableSent = "NOW".equals(source.startMode);
                    state.files.put(key, fileState);
                    saveState();
                } else if (attributes.size() < fileState.offset) {
                    fileState.generation = UUID.randomUUID().toString();
                    fileState.offset = 0;
                    fileState.stableSent = false;
                }
                fileState.path = file.toString();
                long remaining = attributes.size() - fileState.offset;
                if (remaining > 0 && available > SPOOL_ENTRY_RESERVE) {
                    int length = (int) Math.min(Math.min(remaining, local.maxBatchBytes),
                            available - SPOOL_ENTRY_RESERVE);
                    byte[] bytes = read(file, fileState.offset, length);
                    enqueue(source, fileState, attributes, bytes, false);
                    available = local.spoolLimitBytes - spoolBytes();
                    fileState.offset += bytes.length;
                    fileState.lastSize = attributes.size();
                    fileState.stableSent = false;
                    saveState();
                } else if (remaining == 0 && !fileState.stableSent && fileState.lastSize == attributes.size()
                        && available > SPOOL_ENTRY_RESERVE) {
                    enqueue(source, fileState, attributes, new byte[0], true);
                    available = local.spoolLimitBytes - spoolBytes();
                    fileState.stableSent = true;
                    saveState();
                } else {
                    fileState.lastSize = attributes.size();
                }
                report.bytesRead += Math.min(fileState.offset, attributes.size());
            }
            report.lastCollectedAt = Instant.now().toString();
        }
    }

    private SourceReport validate(SourceConfig source) {
        SourceReport report = new SourceReport();
        report.sourceId = source.id;
        report.status = "VALIDATING";
        try {
            Path submitted = java.nio.file.Paths.get(source.path);
            if (!submitted.isAbsolute()) throw new IllegalArgumentException("日志目录必须是绝对路径");
            Path real = submitted.toRealPath();
            if (!Files.isDirectory(real) || !Files.isReadable(real)) throw new IllegalArgumentException("日志目录不存在或不可读");
            boolean allowed = false;
            for (String root : local.allowedRoots) {
                if (real.startsWith(java.nio.file.Paths.get(root).toRealPath())) { allowed = true; break; }
            }
            if (!allowed) throw new IllegalArgumentException("日志目录越过 Agent 允许根");
            FileSystems.getDefault().getPathMatcher("glob:" + source.include);
            if (source.exclude != null && !source.exclude.isEmpty()) FileSystems.getDefault().getPathMatcher("glob:" + source.exclude);
            report.status = spoolBytes() >= local.spoolLimitBytes ? "BLOCKED" : "ACTIVE";
            report.realPath = real.toString();
        } catch (Exception exception) {
            report.status = "ERROR";
            report.error = exception.getMessage();
        }
        return report;
    }

    private List<Path> matchingFiles(SourceConfig source) throws IOException {
        final Path root = java.nio.file.Paths.get(source.path).toRealPath();
        final PathMatcher include = FileSystems.getDefault().getPathMatcher("glob:" + source.include);
        final PathMatcher exclude = source.exclude == null || source.exclude.isEmpty() ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + source.exclude);
        final Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
        List<Path> result = new ArrayList<Path>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && include.matches(path.getFileName())
                        && (exclude == null || !exclude.matches(path.getFileName()))
                        && ("NOW".equals(source.startMode) || Files.getLastModifiedTime(path).toInstant().isAfter(cutoff))) {
                    result.add(path);
                }
            }
        } finally { stream.close(); }
        Collections.sort(result, new Comparator<Path>() {
            public int compare(Path left, Path right) {
                try { return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left)); }
                catch (IOException exception) { return 0; }
            }
        });
        return result;
    }

    private void enqueue(SourceConfig source, FileState file, BasicFileAttributes attributes,
                         byte[] bytes, boolean stable) throws Exception {
        BatchMetadata metadata = new BatchMetadata();
        metadata.batchId = UUID.randomUUID().toString();
        metadata.sourceId = source.id;
        metadata.fileKey = file.fileKey;
        metadata.generation = file.generation;
        metadata.path = file.path;
        metadata.startOffset = file.offset;
        metadata.endOffset = file.offset + bytes.length;
        metadata.fileSize = attributes.size();
        metadata.modifiedAt = attributes.lastModifiedTime().toInstant().toString();
        metadata.charset = source.charset == null ? "UTF-8" : source.charset;
        metadata.checksum = sha256(bytes);
        metadata.stable = stable;
        metadata.createdAt = System.currentTimeMillis();
        String base = String.format("%019d-%s", ++state.sequence, metadata.batchId);
        Path payloadTemporary = spoolDirectory.resolve(base + ".gz.tmp");
        Path payload = spoolDirectory.resolve(base + ".gz");
        GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(payloadTemporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
        try { gzip.write(bytes); } finally { gzip.close(); }
        AgentFiles.moveAtomic(payloadTemporary, payload);
        AgentFiles.writeAtomic(spoolDirectory.resolve(base + ".json"), metadata);
    }

    private synchronized boolean uploadOne() throws Exception {
        List<Path> batches = batchMetadataFiles();
        if (batches.isEmpty()) return false;
        Path metadataPath = batches.get(0);
        BatchMetadata metadata = AgentFiles.read(metadataPath, BatchMetadata.class);
        String base = metadataPath.getFileName().toString().replace(".json", "");
        Path payload = spoolDirectory.resolve(base + ".gz");
        try {
            http.upload(metadata, payload);
            deleteBatch(metadataPath, payload);
            return true;
        } catch (UploadException exception) {
            if (exception.status == 410) {
                deleteBatch(metadataPath, payload);
                return true;
            }
            if (exception.status == 409 && exception.error.expectedOffset != null) {
                rewind(metadata, exception.error.expectedOffset.longValue());
                return true;
            }
            throw exception;
        }
    }

    private synchronized void heartbeat() throws IOException {
        Heartbeat heartbeat = new Heartbeat();
        heartbeat.displayAddress = local.displayAddress == null ? AgentMain.detectAddress() : local.displayAddress;
        heartbeat.spoolBytes = spoolBytes();
        heartbeat.spoolLimitBytes = local.spoolLimitBytes;
        heartbeat.sources.addAll(reports.values());
        http.heartbeat(heartbeat);
    }

    private void rewind(BatchMetadata failed, long expected) throws IOException {
        String key = failed.sourceId + "|" + failed.fileKey;
        FileState file = state.files.get(key);
        if (file != null && file.generation.equals(failed.generation)) {
            file.offset = expected;
            file.stableSent = false;
            saveState();
        }
        for (Path path : batchMetadataFiles()) {
            BatchMetadata metadata = AgentFiles.read(path, BatchMetadata.class);
            if (metadata.sourceId == failed.sourceId && failed.fileKey.equals(metadata.fileKey)
                    && failed.generation.equals(metadata.generation)) {
                deleteBatch(path, spoolDirectory.resolve(path.getFileName().toString().replace(".json", ".gz")));
            }
        }
    }

    private void recoverQueuedOffsets() throws IOException {
        boolean changed = false;
        for (Path path : batchMetadataFiles()) {
            String fileName = path.getFileName().toString();
            try { state.sequence = Math.max(state.sequence, Long.parseLong(fileName.substring(0, 19))); }
            catch (RuntimeException ignored) { /* Batch UUID still prevents filename collisions. */ }
            BatchMetadata metadata = AgentFiles.read(path, BatchMetadata.class);
            String key = metadata.sourceId + "|" + metadata.fileKey;
            FileState file = state.files.get(key);
            if (file == null) {
                file = new FileState();
                file.sourceId = metadata.sourceId;
                file.fileKey = metadata.fileKey;
                state.files.put(key, file);
            }
            if (!metadata.generation.equals(file.generation)) {
                file.generation = metadata.generation;
                file.offset = metadata.endOffset;
            } else {
                file.offset = Math.max(file.offset, metadata.endOffset);
            }
            file.path = metadata.path;
            file.lastSize = Math.max(file.lastSize, metadata.fileSize);
            file.stableSent = metadata.stable;
            changed = true;
        }
        if (changed) saveState();
    }

    private void clearRemovedSources(Set<Long> active) throws IOException {
        reports.keySet().retainAll(active);
        List<String> removeKeys = new ArrayList<String>();
        for (Map.Entry<String, FileState> entry : state.files.entrySet()) {
            if (!active.contains(entry.getValue().sourceId)) removeKeys.add(entry.getKey());
        }
        for (String key : removeKeys) state.files.remove(key);
        for (Path path : batchMetadataFiles()) {
            BatchMetadata metadata = AgentFiles.read(path, BatchMetadata.class);
            if (!active.contains(metadata.sourceId)) {
                deleteBatch(path, spoolDirectory.resolve(path.getFileName().toString().replace(".json", ".gz")));
            }
        }
        saveState();
    }

    private List<Path> batchMetadataFiles() throws IOException {
        List<Path> result = new ArrayList<Path>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(spoolDirectory, "*.json");
        try { for (Path path : stream) result.add(path); } finally { stream.close(); }
        Collections.sort(result);
        return result;
    }

    private long spoolBytes() {
        try {
            long total = 0;
            DirectoryStream<Path> stream = Files.newDirectoryStream(spoolDirectory);
            try { for (Path path : stream) if (Files.isRegularFile(path)) total += Files.size(path); }
            finally { stream.close(); }
            return total;
        } catch (IOException exception) { return 0; }
    }

    private void removeOrphanPayloads() throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(spoolDirectory, "*.gz");
        try {
            for (Path payload : stream) {
                Path metadata = spoolDirectory.resolve(payload.getFileName().toString().replace(".gz", ".json"));
                if (!Files.exists(metadata)) Files.deleteIfExists(payload);
            }
        } finally { stream.close(); }
    }

    private byte[] read(Path path, long offset, int length) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        try {
            file.seek(offset);
            byte[] result = new byte[length];
            int cursor = 0;
            while (cursor < length) {
                int read = file.read(result, cursor, length - cursor);
                if (read < 0) break;
                cursor += read;
            }
            if (cursor == length) return result;
            byte[] truncated = new byte[cursor];
            System.arraycopy(result, 0, truncated, 0, cursor);
            return truncated;
        } finally { file.close(); }
    }

    private String fileKey(Path path, BasicFileAttributes attributes) throws Exception {
        Object key = attributes.fileKey();
        return key == null ? sha256(path.toRealPath().toString().getBytes("UTF-8")) : key.toString();
    }

    private String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private void deleteBatch(Path metadata, Path payload) throws IOException {
        Files.deleteIfExists(metadata);
        Files.deleteIfExists(payload);
    }

    private void saveState() throws IOException { AgentFiles.writeAtomic(statePath, state); }

    private Runnable guard(final String taskName, final Task task) {
        return new Runnable() {
            public void run() {
                try { task.run(); }
                catch (Throwable exception) { System.err.println(Instant.now() + " " + taskName + " failed: " + exception.getMessage()); }
            }
        };
    }

    public void close() {
        executor.shutdown();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private interface Task { void run() throws Exception; }
}
