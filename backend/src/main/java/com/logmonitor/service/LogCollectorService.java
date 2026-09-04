package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.Checkpoint;
import com.logmonitor.model.LogModels.ParsedBatch;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class LogCollectorService {
    private static final Logger log = LoggerFactory.getLogger(LogCollectorService.class);
    private static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    private static final java.util.regex.Pattern EVENT_HEADER = java.util.regex.Pattern.compile(
            "(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[([^]]+)]");
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final LogMonitorProperties properties;
    private final LogMonitorMapper mapper;
    private final LogParser parser;
    private final LogStorageService storage;
    private final LogSourceService sources;
    private final SourceLockRegistry sourceLocks;

    @Autowired
    public LogCollectorService(LogMonitorProperties properties, LogMonitorMapper mapper, LogParser parser,
                               LogStorageService storage, LogSourceService sources, SourceLockRegistry sourceLocks) {
        this.properties = properties;
        this.mapper = mapper;
        this.parser = parser;
        this.storage = storage;
        this.sources = sources;
        this.sourceLocks = sourceLocks;
    }

    public LogCollectorService(LogMonitorProperties properties, LogMonitorMapper mapper, LogParser parser, LogStorageService storage) {
        this(properties, mapper, parser, storage, null, new SourceLockRegistry());
    }

    @Scheduled(fixedDelayString = "${log-monitor.scan-interval-ms:30000}", initialDelayString = "${log-monitor.initial-delay-ms:1000}")
    public void scan() {
        if (sources == null) {
            for (Source source : properties.getSources()) {
                try {
                    scanSource(source);
                } catch (Exception exception) {
                    log.error("Failed to scan source {}", source.getName(), exception);
                }
            }
            return;
        }
        for (Source source : sources.activeSources()) {
            if ("LOCAL".equals(source.getCollectorType())) scanManagedSource(source.getId(), false);
        }
    }

    @Async("sourceScanExecutor")
    public void triggerScan(long sourceId) {
        scanNow(sourceId);
    }

    public void scanNow(long sourceId) {
        scanManagedSource(sourceId, true);
    }

    public Source deleteSource(long sourceId) {
        ReentrantLock lock = sourceLocks.lock(sourceId);
        lock.lock();
        try {
            return sources.delete(sourceId);
        } finally {
            lock.unlock();
        }
    }

    private void scanManagedSource(long sourceId, boolean waitForLock) {
        ReentrantLock lock = sourceLocks.lock(sourceId);
        boolean acquired;
        if (waitForLock) {
            lock.lock();
            acquired = true;
        } else {
            acquired = lock.tryLock();
        }
        if (!acquired) return;
        try {
            Source source = sources.activeSource(sourceId);
            if (source != null && !"MIGRATING".equals(source.getNamespaceMigrationStatus())) scanSource(source);
        } catch (Exception exception) {
            log.error("Failed to scan source {}", sourceId, exception);
        } finally {
            lock.unlock();
        }
    }

    void scanSource(Source source) throws Exception {
        Path root = Path.of(source.getRealPath() == null ? source.getPath() : source.getRealPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("Log source directory does not exist: {}", root);
            return;
        }
        var include = FileSystems.getDefault().getPathMatcher("glob:" + source.getInclude());
        var exclude = FileSystems.getDefault().getPathMatcher("glob:" + source.getExclude());
        Instant cutoff = Instant.now().minus(properties.getInitialLookbackDays(), ChronoUnit.DAYS);
        List<Path> files = new ArrayList<>();
        try (var paths = Files.list(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> include.matches(path.getFileName()) && !exclude.matches(path.getFileName()))
                    .filter(path -> modifiedAt(path).isAfter(cutoff))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(this::modifiedAt).reversed());
        for (Path file : files) {
            boolean more;
            do { more = collectFile(source, file); } while (more);
        }
    }

    private boolean collectFile(Source source, Path file) {
        Instant now = Instant.now();
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String fileKey = stableFileKey(file, attrs);
            Map<String, Object> row = mapper.findCheckpoint(source.getId(), fileKey);
            boolean existing = row != null;
            long previousOffset = existing ? number(value(row, "byte_offset")) : 0L;
            long previousSize = existing ? number(value(row, "file_size")) : 0L;
            long pendingOffset = existing ? number(value(row, "pending_offset")) : previousOffset;
            String pendingEvent = existing ? string(value(row, "pending_text")) : "";
            String pendingBytes = existing ? string(value(row, "pending_bytes")) : "";
            Instant lastEventAt = existing ? instant(value(row, "last_event_at")) : null;
            if (attrs.size() < previousOffset) {
                previousOffset = 0;
                previousSize = 0;
                pendingOffset = 0;
                pendingEvent = "";
                pendingBytes = "";
                lastEventAt = null;
            }

            long batchEnd = Math.min(attrs.size(), previousOffset + MAX_BATCH_BYTES);
            byte[] bytes = readBytes(file, previousOffset, batchEnd);
            DecodeResult decoded = decode(source, pendingBytes, bytes);
            String combined = pendingEvent + decoded.text();
            long combinedOffset = pendingEvent.isEmpty()
                    ? Math.max(0, previousOffset - decoded.prefixBytes())
                    : pendingOffset;
            EventSplit split = splitCompleteEvents(combined);
            boolean stable = batchEnd == attrs.size() && bytes.length == 0 && previousSize == attrs.size()
                    && endsWithLineBreak(combined);
            String ready = stable ? combined : split.complete();
            String nextPending = stable ? "" : split.pending();
            ParsedBatch batch = ready.isBlank()
                    ? new ParsedBatch(List.of(), List.of(), 0, null)
                    : parser.parse(source, ready, file.toString(), combinedOffset);
            Instant nextLastEventAt = later(lastEventAt, batch.lastEventAt());
            long nextPendingOffset = nextPending.isEmpty()
                    ? batchEnd
                    : combinedOffset + split.complete().getBytes(source.getCharset()).length;
            Checkpoint checkpoint = new Checkpoint(source.getName(), source.getInstanceKey(), source.getId(), fileKey,
                    "local", file.toString(), batchEnd,
                    nextPendingOffset, nextPending, decoded.remainingBase64(), "ACTIVE", attrs.size(),
                    attrs.lastModifiedTime().toInstant(), now, nextLastEventAt, null, batch.parseErrors());
            storage.store(batch, checkpoint, existing);
            return batchEnd < attrs.size();
        } catch (Exception e) {
            log.error("Failed to collect {}", file, e);
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                String key = stableFileKey(file, attrs);
                Map<String, Object> row = mapper.findCheckpoint(source.getId(), key);
                long offset = row == null ? 0 : number(value(row, "byte_offset"));
                Checkpoint failed = new Checkpoint(source.getName(), source.getInstanceKey(), source.getId(), key,
                        "local", file.toString(), offset,
                        row == null ? offset : number(value(row, "pending_offset")),
                        row == null ? "" : string(value(row, "pending_text")),
                        row == null ? "" : string(value(row, "pending_bytes")), "ERROR", attrs.size(),
                        attrs.lastModifiedTime().toInstant(), now,
                        row == null ? null : instant(value(row, "last_event_at")), abbreviate(e.getMessage()), 1);
                storage.updateCheckpointOnly(failed, row != null);
            } catch (Exception ignored) {
                log.error("Could not persist collector failure for {}", file);
            }
            return false;
        }
    }

    private byte[] readBytes(Path path, long start, long end) throws Exception {
        if (end <= start) return new byte[0];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            file.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = end - start;
            while (remaining > 0) {
                int read = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toByteArray();
        }
    }

    private DecodeResult decode(Source source, String pendingBase64, byte[] bytes) throws Exception {
        byte[] prefix = pendingBase64 == null || pendingBase64.isBlank() ? new byte[0] : Base64.getDecoder().decode(pendingBase64);
        byte[] all = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(bytes, 0, all, prefix.length, bytes.length);
        var decoder = source.getCharset().newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer input = ByteBuffer.wrap(all);
        CharBuffer output = CharBuffer.allocate(Math.max(1, (int) (all.length * decoder.maxCharsPerByte()) + 1));
        CoderResult result = decoder.decode(input, output, false);
        output.flip();
        byte[] remaining = new byte[input.remaining()];
        input.get(remaining);
        return new DecodeResult(output.toString(), Base64.getEncoder().encodeToString(remaining), prefix.length);
    }

    private EventSplit splitCompleteEvents(String text) {
        var matcher = EVENT_HEADER.matcher(text);
        List<EventHeader> headers = new ArrayList<>();
        while (matcher.find()) {
            headers.add(new EventHeader(matcher.start(), LocalDateTime.parse(matcher.group(1), EVENT_TIME),
                    matcher.group(2), matcher.group(3).trim()));
        }
        if (headers.size() <= 1) return new EventSplit("", text);
        int retain = headers.size() - 1;
        while (retain > 0) {
            EventHeader current = headers.get(retain);
            EventHeader previous = headers.get(retain - 1);
            if (!"ERROR".equals(current.level()) || !"ERROR".equals(previous.level()) ||
                    !current.thread().equals(previous.thread()) ||
                    Duration.between(previous.at(), current.at()).abs().toMillis() > 2000) break;
            retain--;
        }
        int splitAt = headers.get(retain).offset();
        return new EventSplit(text.substring(0, splitAt), text.substring(splitAt));
    }

    private String stableFileKey(Path path, BasicFileAttributes attrs) throws Exception {
        Object key = attrs.fileKey();
        if (key != null) return key.toString();
        FileStore store = Files.getFileStore(path);
        return store.name() + ":" + path.toRealPath();
    }
    private Instant modifiedAt(Path path) {
        try { return Files.getLastModifiedTime(path).toInstant(); } catch (Exception e) { return Instant.EPOCH; }
    }
    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private String string(Object value) { return value == null ? "" : value.toString(); }
    private Object value(Map<String, Object> row, String expected) {
        if (row.containsKey(expected)) return row.get(expected);
        String normalized = expected.replace("_", "");
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().replace("_", "").equalsIgnoreCase(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
    private Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        throw new IllegalArgumentException("Unsupported timestamp value: " + value.getClass().getName());
    }
    private Instant later(Instant left, Instant right) {
        if (left == null) return right;
        return right != null && right.isAfter(left) ? right : left;
    }
    private boolean endsWithLineBreak(String value) {
        return value.endsWith("\n") || value.endsWith("\r");
    }
    private String abbreviate(String value) { return value == null ? "Unknown collector error" : value.substring(0, Math.min(2000, value.length())); }
    private record DecodeResult(String text, String remainingBase64, int prefixBytes) {}
    private record EventSplit(String complete, String pending) {}
    private record EventHeader(int offset, LocalDateTime at, String level, String thread) {}
}
