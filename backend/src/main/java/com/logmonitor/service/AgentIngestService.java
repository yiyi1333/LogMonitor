package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.AgentMapper;
import com.logmonitor.mapper.AgentMapper.BatchInsert;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.Checkpoint;
import com.logmonitor.model.ApiModels.AgentBatchAck;
import com.logmonitor.model.ApiModels.AgentBatchMetadata;
import com.logmonitor.model.LogModels.ParsedBatch;
import com.logmonitor.security.AgentPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentIngestService {
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern EVENT_HEADER = Pattern.compile(
            "(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[([^]]+)]");
    private final AgentMapper agents;
    private final LogMonitorMapper mapper;
    private final LogSourceService sources;
    private final LogParser parser;
    private final LogStorageService storage;
    private final SourceLockRegistry locks;
    private final PipelineMetrics metrics;

    public AgentIngestService(AgentMapper agents, LogMonitorMapper mapper, LogSourceService sources,
                              LogParser parser, LogStorageService storage, SourceLockRegistry locks, PipelineMetrics metrics) {
        this.agents = agents;
        this.mapper = mapper;
        this.sources = sources;
        this.parser = parser;
        this.storage = storage;
        this.locks = locks;
        this.metrics = metrics;
    }

    @Transactional
    public AgentBatchAck accept(AgentPrincipal agent, AgentBatchMetadata metadata, InputStream compressed,
                                long compressedSize) {
        validateMetadata(metadata, compressedSize);
        ReentrantLock lock = locks.lock(metadata.sourceId());
        long lockStart = System.nanoTime();
        lock.lock();
        metrics.elapsed("sourceLock", lockStart);
        boolean unlockAfterTransaction = holdUntilTransactionCompletion(lock);
        try {
            if (agents.batchExists(metadata.batchId()) > 0) {
                return new AgentBatchAck("ACK", metadata.endOffset());
            }
            Source source = sources.activeSource(metadata.sourceId());
            if (source == null || !"AGENT".equals(source.getCollectorType())
                    || source.getAgentId() == null || source.getAgentId() != agent.id()) {
                throw error("SOURCE_DELETED", HttpStatus.GONE, "日志源已删除或不属于当前 Agent");
            }
            if ("MIGRATING".equals(source.getNamespaceMigrationStatus())) {
                throw error("SOURCE_MIGRATING", HttpStatus.LOCKED, "日志源命名空间迁移中");
            }
            if (!"ACTIVE".equals(source.getValidationStatus())) {
                throw error("SOURCE_NOT_READY", HttpStatus.CONFLICT, "日志源尚未通过 Agent 校验");
            }
            long decompressionStart = System.nanoTime();
            byte[] raw = gunzip(compressed);
            metrics.elapsed("decompress", decompressionStart);
            metrics.add("rawBytes", raw.length);
            if (metadata.endOffset() - metadata.startOffset() != raw.length) {
                throw error("BATCH_OFFSET_INVALID", HttpStatus.BAD_REQUEST, "批次偏移与内容长度不一致");
            }
            if (!sha256(raw).equalsIgnoreCase(metadata.checksum())) {
                throw error("BATCH_CHECKSUM_INVALID", HttpStatus.BAD_REQUEST, "批次校验失败");
            }
            validateFilePath(source, metadata.path());

            Map<String, Object> row = mapper.findCheckpoint(source.getId(), metadata.fileKey());
            boolean existing = row != null;
            String previousGeneration = existing ? string(value(row, "stream_generation")) : metadata.generation();
            long expected = existing ? number(value(row, "byte_offset"))
                    : ("NOW".equals(source.getStartMode()) ? metadata.startOffset() : 0L);
            boolean reset = existing && !previousGeneration.equals(metadata.generation());
            if (reset && metadata.startOffset() != 0) {
                throw conflict(0, "新文件代次必须从偏移 0 开始");
            }
            if (!reset && metadata.startOffset() != expected) {
                throw conflict(expected, "批次偏移不连续");
            }

            String pending = reset || !existing ? "" : string(value(row, "pending_text"));
            long pendingOffset = reset || !existing ? metadata.startOffset() : number(value(row, "pending_offset"));
            byte[] prefix = reset || !existing ? new byte[0] : decodeBase64(string(value(row, "pending_bytes")));
            DecodeResult decoded = decode(source, prefix, raw);
            String combined = pending + decoded.text();
            long combinedOffset = pending.isEmpty() ? Math.max(0, metadata.startOffset() - prefix.length) : pendingOffset;
            EventSplit split = split(combined);
            boolean flush = metadata.stable() && metadata.endOffset() == metadata.fileSize()
                    && (combined.endsWith("\n") || combined.endsWith("\r"));
            String ready = flush ? combined : split.complete();
            String nextPending = flush ? "" : split.pending();
            long parseStart = System.nanoTime();
            ParsedBatch batch = ready.isBlank() ? new ParsedBatch(List.of(), List.of(), 0, null)
                    : parser.parse(source, ready, metadata.path(), combinedOffset);
            metrics.elapsed("parse", parseStart);
            long nextPendingOffset = nextPending.isEmpty() ? metadata.endOffset()
                    : combinedOffset + split.complete().getBytes(source.getCharset()).length;
            Checkpoint checkpoint = new Checkpoint(source.getName(), source.getInstanceKey(), source.getId(),
                    metadata.fileKey(), metadata.generation(), metadata.path(), metadata.endOffset(),
                    nextPendingOffset, nextPending, Base64.getEncoder().encodeToString(decoded.remaining()),
                    "ACTIVE", metadata.fileSize(), metadata.modifiedAt(), Instant.now(), batch.lastEventAt(), null,
                    batch.parseErrors());
            long writeStart = System.nanoTime();
            storage.store(batch, checkpoint, existing);
            metrics.elapsed("write", writeStart);
            agents.insertBatch(new BatchInsert(metadata.batchId(), agent.id(), source.getId(), metadata.fileKey(),
                    metadata.generation(), metadata.startOffset(), metadata.endOffset(), metadata.checksum()));
            return new AgentBatchAck("ACK", metadata.endOffset());
        } finally {
            if (!unlockAfterTransaction) lock.unlock();
        }
    }

    private boolean holdUntilTransactionCompletion(ReentrantLock lock) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                metrics.add(status == STATUS_COMMITTED ? "ingestCommitted" : "ingestRolledBack", 1);
                lock.unlock();
            }
        });
        return true;
    }

    private void validateMetadata(AgentBatchMetadata metadata, long compressedSize) {
        if (metadata == null || metadata.batchId() == null || metadata.batchId().isBlank()
                || metadata.fileKey() == null || metadata.fileKey().isBlank()
                || metadata.generation() == null || metadata.generation().isBlank()
                || metadata.path() == null || metadata.charset() == null || metadata.checksum() == null
                || metadata.startOffset() < 0 || metadata.endOffset() < metadata.startOffset()
                || compressedSize < 0 || compressedSize > 5L * 1024 * 1024) {
            throw error("INVALID_BATCH", HttpStatus.BAD_REQUEST, "批次元数据无效");
        }
    }

    private byte[] gunzip(InputStream input) {
        try (GZIPInputStream gzip = new GZIPInputStream(input); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            int total = 0;
            while ((read = gzip.read(buffer)) >= 0) {
                total += read;
                if (total > AgentService.MAX_BATCH_BYTES) {
                    throw error("BATCH_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "解压后的批次超过 4MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (AgentProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw error("INVALID_GZIP", HttpStatus.BAD_REQUEST, "批次压缩内容无效");
        }
    }

    private DecodeResult decode(Source source, byte[] prefix, byte[] bytes) {
        byte[] all = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(bytes, 0, all, prefix.length, bytes.length);
        var decoder = source.getCharset().newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer input = ByteBuffer.wrap(all);
        CharBuffer output = CharBuffer.allocate(Math.max(1, (int) (all.length * decoder.maxCharsPerByte()) + 1));
        decoder.decode(input, output, false);
        output.flip();
        byte[] remaining = new byte[input.remaining()];
        input.get(remaining);
        return new DecodeResult(output.toString(), remaining);
    }

    private EventSplit split(String text) {
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
            if (!"ERROR".equals(current.level()) || !"ERROR".equals(previous.level())
                    || !current.thread().equals(previous.thread())
                    || Duration.between(previous.at(), current.at()).abs().toMillis() > 2000) {
                break;
            }
            retain--;
        }
        int splitAt = headers.get(retain).offset();
        return new EventSplit(text.substring(0, splitAt), text.substring(splitAt));
    }

    private void validateFilePath(Source source, String filePath) {
        try {
            var file = java.nio.file.Path.of(filePath).normalize();
            var root = java.nio.file.Path.of(source.getRealPath()).normalize();
            if (!file.isAbsolute() || !file.startsWith(root)) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw error("SOURCE_PATH_NOT_ALLOWED", HttpStatus.BAD_REQUEST, "批次文件不在日志源目录内");
        }
    }

    private Object value(Map<String, Object> row, String expected) {
        String normalized = expected.replace("_", "");
        return row.entrySet().stream().filter(entry -> entry.getKey().replace("_", "").equalsIgnoreCase(normalized))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private String string(Object value) { return value == null ? "" : value.toString(); }
    private byte[] decodeBase64(String value) { return value.isBlank() ? new byte[0] : Base64.getDecoder().decode(value); }
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private AgentProtocolException conflict(long expected, String message) {
        return new AgentProtocolException("BATCH_OFFSET_CONFLICT", HttpStatus.CONFLICT, message, expected);
    }
    private AgentProtocolException error(String code, HttpStatus status, String message) {
        return new AgentProtocolException(code, status, message);
    }
    private record DecodeResult(String text, byte[] remaining) {}
    private record EventSplit(String complete, String pending) {}
    private record EventHeader(int offset, LocalDateTime at, String level, String thread) {}
}
