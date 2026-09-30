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
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
    private final ExecutorService uploadExecutor;
    private final TreeMap<Path, BatchMetadata> queue = new TreeMap<Path, BatchMetadata>();
    private final Set<Long> inFlight = new HashSet<Long>();
    private final Map<Long, Long> retryAt = new HashMap<Long, Long>();
    private final Map<Long, Integer> failures = new HashMap<Long, Integer>();
    private long cachedSpoolBytes;
    private boolean diskHealthy = true;
    private long readBytes, uploadedBytes, retries, heartbeatNanos, heartbeatCount;
    private long previousReadBytes, previousUploadedBytes, previousReportNanos = System.nanoTime();
    private int scanCursor;
    private long lastUploadSource = -1;
    private volatile boolean closed;
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
        String configuredWorkers = System.getenv("AGENT_UPLOAD_WORKERS");
        int workers = configuredWorkers == null ? 2 : Integer.parseInt(configuredWorkers);
        if (workers < 1 || workers > 4) throw new IllegalArgumentException("AGENT_UPLOAD_WORKERS must be 1..4");
        this.uploadExecutor = Executors.newFixedThreadPool(workers);
        this.http = new AgentHttpClient(local.serverUrl, local.token, local.allowHttp);
        Files.createDirectories(spoolDirectory);
        this.state = Files.exists(statePath) ? AgentFiles.read(statePath, RuntimeState.class) : new RuntimeState();
        this.remote = Files.exists(remoteConfigPath) ? AgentFiles.read(remoteConfigPath, RemoteConfig.class) : new RemoteConfig();
        removeOrphanPayloads();
        recoverQueuedOffsets();
        rebuildQueue();
    }

    void run() throws InterruptedException {
        executor.scheduleWithFixedDelay(guard("config", new Task() { public void run() throws Exception { pollConfiguration(); }}), 0, 10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(guard("scan", new Task() { public void run() throws Exception { scan(); }}), 1, 1, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(guard("heartbeat", new Task() { public void run() throws Exception { heartbeat(); }}), 3, 30, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(guard("metrics", new Task() { public void run() throws Exception { reportMetrics(); }}), 60, 60, TimeUnit.SECONDS);
        int workers = ((java.util.concurrent.ThreadPoolExecutor) uploadExecutor).getCorePoolSize();
        for (int i = 0; i < workers; i++) uploadExecutor.submit(new Runnable() {
            public void run() {
                while (!closed && !Thread.currentThread().isInterrupted()) {
                    try { if (!uploadOne()) Thread.sleep(1000); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
                    catch (Exception exception) { System.err.println("Agent upload failed; retry scheduled"); }
                }
            }
        });
        new CountDownLatch(1).await();
    }

    void runOnce() throws Exception {
        pollConfiguration();
        scan();
        while (uploadOne()) {}
        heartbeat();
    }

    private void pollConfiguration() throws IOException {
        long revision;
        synchronized (this) { revision = remote == null ? 0 : remote.revision; }
        RemoteConfig update = http.configuration(revision);
        if (update == null) return;
        synchronized (this) {
            Set<Long> active = new HashSet<Long>();
            for (SourceConfig source : update.sources) active.add(source.id);
            // Removed sources become ineligible immediately; physical cleanup waits for the in-flight upload.
            remote = update;
            clearRemovedSources(active);
            local.configRevision = update.revision;
            AgentFiles.writeAtomic(remoteConfigPath, remote);
            AgentFiles.writeAtomic(configPath, local);
            AgentFiles.ownerOnly(configPath);
        }
    }

    private void scan() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        List<ScanFile> files = new ArrayList<ScanFile>();
        synchronized (this) {
            if (remote == null || remote.sources == null) return;
            for (SourceConfig source : remote.sources) {
                SourceReport report = validate(source);
                reports.put(source.id, report);
                if (!"ACTIVE".equals(report.status)) continue;
                List<Path> paths = matchingFiles(source);
                report.files = paths.size();
                for (Path path : paths) {
                    report.totalBytes += Files.size(path);
                    files.add(new ScanFile(source, path));
                }
                report.lastCollectedAt = Instant.now().toString();
            }
        }
        if (files.isEmpty()) { Thread.sleep(1000); return; }
        int withoutProgress = 0;
        while (System.nanoTime() < deadline && withoutProgress < files.size()) {
            boolean progress;
            synchronized (this) {
                ScanFile candidate = files.get(Math.floorMod(scanCursor++, files.size()));
                progress = active(candidate.source.id) && scanFile(candidate.source, candidate.path);
            }
            withoutProgress = progress ? 0 : withoutProgress + 1;
        }
        if (withoutProgress >= files.size()) Thread.sleep(1000);
    }

    private boolean scanFile(SourceConfig source, Path file) throws Exception {
        long available = local.spoolLimitBytes - spoolBytes();
        if (available <= SPOOL_ENTRY_RESERVE) { reports.get(source.id).status = "BLOCKED"; return false; }
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        String fileKey = fileKey(file, attributes);
        String key = source.id + "|" + fileKey;
        FileState fileState = state.files.get(key);
        if (fileState == null) {
            fileState = new FileState(); fileState.sourceId = source.id; fileState.fileKey = fileKey;
            fileState.generation = UUID.randomUUID().toString(); fileState.path = file.toString();
            fileState.offset = "NOW".equals(source.startMode) ? attributes.size() : 0;
            fileState.lastSize = attributes.size(); fileState.stableSent = "NOW".equals(source.startMode);
            state.files.put(key, fileState); saveState();
        } else if (attributes.size() < fileState.offset) {
            if (inFlight.contains(source.id)) return false;
            discardStream(source.id, fileKey, fileState.generation);
            fileState.generation = UUID.randomUUID().toString(); fileState.offset = 0; fileState.stableSent = false;
            saveState();
        }
        fileState.path = file.toString();
        long remaining = attributes.size() - fileState.offset;
        if (remaining > 0) {
            int length = (int) Math.min(Math.min(remaining, Math.min(local.maxBatchBytes, 4 * 1024 * 1024)), available-SPOOL_ENTRY_RESERVE);
            byte[] bytes = read(file, fileState.offset, length);
            if (bytes.length == 0) return false;
            enqueue(source, fileState, attributes, bytes, false);
            fileState.offset += bytes.length; fileState.lastSize = attributes.size(); fileState.stableSent = false;
            readBytes += bytes.length; reports.get(source.id).bytesRead += bytes.length;
            saveState(); return true;
        }
        if (!fileState.stableSent && fileState.lastSize == attributes.size()) {
            enqueue(source, fileState, attributes, new byte[0], true);
            fileState.stableSent = true; saveState(); return true;
        }
        fileState.lastSize = attributes.size(); return false;
    }

    private static final class ScanFile {
        final SourceConfig source; final Path path;
        ScanFile(SourceConfig source, Path path) { this.source = source; this.path = path; }
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
        try {
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
        Path metadataPath = spoolDirectory.resolve(base + ".json");
        AgentFiles.writeAtomic(metadataPath, metadata);
        queue.put(metadataPath, metadata);
        cachedSpoolBytes += Files.size(metadataPath) + Files.size(payload);
        } catch (IOException failure) { diskHealthy=false; throw failure; }
    }

    private boolean uploadOne() throws Exception {
        Path metadataPath = null;
        BatchMetadata metadata = null;
        synchronized (this) {
            Set<Long> examined = new HashSet<Long>();
            Map<Long, Path> candidates = new TreeMap<Long, Path>();
            for (Map.Entry<Path, BatchMetadata> entry : queue.entrySet()) {
                long source = entry.getValue().sourceId;
                if (!examined.add(source)) continue;
                if (active(source) && !inFlight.contains(source) && retryTime(source) <= System.currentTimeMillis())
                    candidates.put(source, entry.getKey());
            }
            if (candidates.isEmpty()) return false;
            for (Map.Entry<Long, Path> candidate : candidates.entrySet()) {
                if (candidate.getKey() > lastUploadSource) { metadataPath = candidate.getValue(); break; }
            }
            if (metadataPath == null) metadataPath = candidates.values().iterator().next();
            metadata = queue.get(metadataPath); lastUploadSource = metadata.sourceId; inFlight.add(metadata.sourceId);
        }
        Path payload = spoolDirectory.resolve(metadataPath.getFileName().toString().replace(".json", ".gz"));
        try {
            AgentModels.BatchAck ack = http.upload(metadata, payload);
            if (ack == null || !"ACK".equals(ack.status) || ack.expectedOffset != metadata.endOffset)
                throw new IOException("Invalid upload ACK");
            synchronized (this) {
                deleteBatch(metadataPath, payload); failures.remove(metadata.sourceId); retryAt.remove(metadata.sourceId);
                uploadedBytes += metadata.endOffset-metadata.startOffset;
            }
            return true;
        } catch (UploadException exception) {
            synchronized (this) {
                if (exception.status == 410) { deleteBatch(metadataPath, payload); return true; }
                if (exception.status == 409 && exception.error.expectedOffset != null) {
                    rewind(metadata, exception.error.expectedOffset.longValue()); return true;
                }
                scheduleRetry(metadata.sourceId, exception.retryAfterMillis);
            }
            return false;
        } catch (IOException exception) {
            synchronized (this) { scheduleRetry(metadata.sourceId, 0); }
            return false;
        } finally {
            synchronized (this) {
                inFlight.remove(metadata.sourceId);
                if (!active(metadata.sourceId)) {
                    Set<Long> active = new HashSet<Long>();
                    for (SourceConfig source : remote.sources) active.add(source.id);
                    clearRemovedSources(active);
                }
            }
        }
    }

    private long retryTime(long source) { Long at = retryAt.get(source); return at == null ? 0 : at; }
    private void scheduleRetry(long source, long serverDelay) {
        int count = failures.containsKey(source) ? failures.get(source)+1 : 1;
        failures.put(source, count);
        long base = Math.min(30000, 1000L << Math.min(count-1, 5));
        long delay = Math.max(serverDelay, Math.min(30000, base + ThreadLocalRandom.current().nextLong(base/4+1)));
        retryAt.put(source, System.currentTimeMillis()+delay); retries++;
    }
    private boolean active(long sourceId) {
        for (SourceConfig source : remote.sources) if (source.id == sourceId) return true;
        return false;
    }

    private void heartbeat() throws IOException {
        Heartbeat heartbeat = new Heartbeat();
        synchronized (this) {
            heartbeat.displayAddress = local.displayAddress == null ? AgentMain.detectAddress() : local.displayAddress;
            heartbeat.spoolBytes = cachedSpoolBytes; heartbeat.spoolLimitBytes = local.spoolLimitBytes;
            // Copy mutable reports before releasing the state lock.
            for (SourceReport report : reports.values()) {
                heartbeat.sources.add(AgentFiles.JSON.readValue(AgentFiles.JSON.writeValueAsBytes(report), SourceReport.class));
            }
        }
        long started = System.nanoTime();
        try { http.heartbeat(heartbeat); }
        finally { synchronized (this) { heartbeatNanos += System.nanoTime()-started; heartbeatCount++; } }
    }

    private synchronized void reportMetrics() throws IOException {
        try { rebuildQueue(); diskHealthy = true; }
        catch (IOException exception) { diskHealthy = false; throw exception; }
        Map<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        values.put("type", "agent_metrics"); values.put("readBytes", readBytes); values.put("uploadedBytes", uploadedBytes);
        long now = System.nanoTime();
        double seconds = Math.max(0.001, (now-previousReportNanos)/1e9);
        values.put("readBytesPerSecond", (readBytes-previousReadBytes)/seconds);
        values.put("uploadBytesPerSecond", (uploadedBytes-previousUploadedBytes)/seconds);
        previousReadBytes=readBytes; previousUploadedBytes=uploadedBytes; previousReportNanos=now;
        values.put("spoolBytes", cachedSpoolBytes); values.put("retryCount", retries); values.put("inFlight", inFlight.size());
        long oldest = System.currentTimeMillis();
        for (BatchMetadata entry : queue.values()) oldest = Math.min(oldest, entry.createdAt);
        values.put("oldestBatchMillis", Math.max(0, System.currentTimeMillis()-oldest));
        values.put("heartbeatNanos", heartbeatNanos); values.put("heartbeatCount", heartbeatCount);
        System.out.println(AgentFiles.JSON.writeValueAsString(values));
    }

    private void rebuildQueue() throws IOException {
        TreeMap<Path, BatchMetadata> rebuilt = new TreeMap<Path, BatchMetadata>();
        long bytes = 0;
        DirectoryStream<Path> stream = Files.newDirectoryStream(spoolDirectory);
        try {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                bytes += Files.size(path);
                if (path.getFileName().toString().endsWith(".json")) {
                    Path payload = spoolDirectory.resolve(path.getFileName().toString().replace(".json", ".gz"));
                    if (!Files.isRegularFile(payload)) throw new IOException("Queued batch payload missing");
                    rebuilt.put(path, AgentFiles.read(path, BatchMetadata.class));
                }
            }
        } finally { stream.close(); }
        queue.clear(); queue.putAll(rebuilt); cachedSpoolBytes = bytes;
    }

    private void discardStream(long source, String fileKey, String generation) throws IOException {
        for (Path path : new ArrayList<Path>(queue.keySet())) {
            BatchMetadata entry = queue.get(path);
            if (entry.sourceId == source && fileKey.equals(entry.fileKey) && generation.equals(entry.generation))
                deleteBatch(path, spoolDirectory.resolve(path.getFileName().toString().replace(".json", ".gz")));
        }
    }

    private void rewind(BatchMetadata failed, long expected) throws IOException {
        String key = failed.sourceId + "|" + failed.fileKey;
        FileState file = state.files.get(key);
        if (file != null && file.generation.equals(failed.generation)) {
            file.offset = expected;
            file.stableSent = false;
            saveState();
        }
        for (Path path : new ArrayList<Path>(queue.keySet())) {
            BatchMetadata metadata = queue.get(path);
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
            if (!active.contains(entry.getValue().sourceId) && !inFlight.contains(entry.getValue().sourceId)) removeKeys.add(entry.getKey());
        }
        for (String key : removeKeys) state.files.remove(key);
        for (Path path : new ArrayList<Path>(queue.keySet())) {
            BatchMetadata metadata = queue.get(path);
            if (!active.contains(metadata.sourceId) && !inFlight.contains(metadata.sourceId)) {
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

    private long spoolBytes() throws IOException {
        if (!diskHealthy) throw new IOException("Spool accounting unavailable; reads stopped");
        return cachedSpoolBytes;
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
        long bytes = (Files.exists(metadata) ? Files.size(metadata) : 0) + (Files.exists(payload) ? Files.size(payload) : 0);
        try {
            Files.deleteIfExists(metadata); Files.deleteIfExists(payload);
            queue.remove(metadata); cachedSpoolBytes = Math.max(0, cachedSpoolBytes-bytes);
        } catch (IOException exception) { diskHealthy = false; throw exception; }
    }

    private void saveState() throws IOException {
        try { AgentFiles.writeAtomic(statePath, state); }
        catch (IOException failure) { diskHealthy=false; throw failure; }
    }

    private Runnable guard(final String taskName, final Task task) {
        return new Runnable() {
            public void run() {
                try { task.run(); }
                catch (Throwable exception) { System.err.println(Instant.now() + " " + taskName + " failed: " + exception.getClass().getSimpleName()); }
            }
        };
    }

    public void close() {
        closed = true;
        uploadExecutor.shutdownNow();
        executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); uploadExecutor.awaitTermination(35, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private interface Task { void run() throws Exception; }
}
