package com.logmonitor.service;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.Checkpoint;
import com.logmonitor.mapper.LogMonitorMapper.ErrorGroupInsert;
import com.logmonitor.mapper.LogMonitorMapper.OccurrenceInsert;
import com.logmonitor.model.LogModels.AccessEvent;
import com.logmonitor.model.LogModels.ErrorEvent;
import com.logmonitor.model.LogModels.ParsedBatch;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogStorageService {
    private final LogMonitorMapper mapper;

    public LogStorageService(LogMonitorMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void store(ParsedBatch batch, Checkpoint checkpoint, boolean existingCheckpoint) {
        Map<String,AccessEvent> accessCandidates = new LinkedHashMap<>();
        for (AccessEvent event : batch.accesses()) accessCandidates.putIfAbsent(event.eventKey(),event);
        Set<String> existingAccess = new HashSet<>();
        chunks(new ArrayList<>(accessCandidates.keySet()), key -> 128, keys -> existingAccess.addAll(mapper.existingAccessKeys(keys)));
        existingAccess.forEach(accessCandidates::remove);
        Map<AccessBucket,com.logmonitor.mapper.LogMonitorMapper.AccessBucketInsert> lookup = new LinkedHashMap<>();
        for (AccessEvent access : accessCandidates.values()) {
            AccessBucket bucket = new AccessBucket(access.service(),access.uri(),sha256(access.uri()),access.occurredAt().truncatedTo(ChronoUnit.MINUTES));
            lookup.putIfAbsent(bucket,new com.logmonitor.mapper.LogMonitorMapper.AccessBucketInsert(bucket.service(),checkpoint.instanceKey(),checkpoint.sourceId(),bucket.uri(),bucket.uriHash(),bucket.minute(),0));
        }
        Map<String,Instant> baselines = new HashMap<>();
        chunks(new ArrayList<>(lookup.values()), row -> 256, rows -> {
            for (Map<String,Object> row : mapper.accessBaselines(checkpoint.sourceId(),rows)) {
                baselines.put(value(row,"uri_hash") + "|" + instant(value(row,"minute_at")),instant(value(row,"baseline_until")));
            }
        });
        List<com.logmonitor.mapper.LogMonitorMapper.EventKeyInsert> acceptedKeys = new ArrayList<>();
        Map<AccessBucket,Long> buckets = new LinkedHashMap<>();
        for (AccessEvent access : accessCandidates.values()) {
            AccessBucket bucket = new AccessBucket(access.service(),access.uri(),sha256(access.uri()),access.occurredAt().truncatedTo(ChronoUnit.MINUTES));
            Instant baseline = baselines.get(bucket.uriHash()+"|"+bucket.minute());
            if (baseline != null && !access.occurredAt().isAfter(baseline)) continue;
            acceptedKeys.add(new com.logmonitor.mapper.LogMonitorMapper.EventKeyInsert(access.eventKey(),access.occurredAt()));
            buckets.merge(bucket,1L,Long::sum);
        }
        chunks(acceptedKeys,row -> 160,mapper::insertAccessKeys);
        List<com.logmonitor.mapper.LogMonitorMapper.AccessBucketInsert> bucketRows = new ArrayList<>();
        buckets.forEach((bucket,count) -> bucketRows.add(new com.logmonitor.mapper.LogMonitorMapper.AccessBucketInsert(
                bucket.service(),checkpoint.instanceKey(),checkpoint.sourceId(),bucket.uri(),bucket.uriHash(),bucket.minute(),count)));
        chunks(bucketRows,row -> bytes(row.service(),row.instanceKey(),row.uri(),row.uriHash())+128,mapper::upsertAccessBuckets);

        Map<String,ErrorEvent> errorCandidates = new LinkedHashMap<>();
        for (ErrorEvent event : batch.errors()) errorCandidates.putIfAbsent(event.eventKey(),event);
        Set<String> existingErrors = new HashSet<>();
        chunks(new ArrayList<>(errorCandidates.keySet()),key -> 128,keys -> existingErrors.addAll(mapper.existingErrorKeys(keys)));
        existingErrors.forEach(errorCandidates::remove);
        // Sorted fingerprint order gives concurrent uploads the same group-lock order.
        Map<String,List<ErrorEvent>> groups = new TreeMap<>();
        for (ErrorEvent event : errorCandidates.values()) groups.computeIfAbsent(event.fingerprint(),ignored -> new ArrayList<>()).add(event);
        List<ErrorGroupInsert> groupRows = new ArrayList<>();
        for (List<ErrorEvent> errors : groups.values()) {
            ErrorEvent representative = errors.get(0);
            ErrorGroupInsert row = new ErrorGroupInsert();
            row.fingerprint=representative.fingerprint();row.signature=representative.signature();row.service=representative.service();
            row.category=representative.category();row.exceptionClass=representative.exceptionClass();row.summary=representative.summary();
            row.firstSeen=errors.stream().map(ErrorEvent::occurredAt).min(Instant::compareTo).orElseThrow();
            row.lastSeen=errors.stream().map(ErrorEvent::occurredAt).max(Instant::compareTo).orElseThrow();row.count=errors.size();
            row.inferredUri=errors.stream().map(ErrorEvent::inferredUri).filter(v -> v!=null && !v.isBlank()).findFirst().orElse(null);
            groupRows.add(row);
        }
        chunks(groupRows,row -> bytes(row.fingerprint,row.signature,row.service,row.category,row.exceptionClass,row.summary,row.inferredUri)+256,mapper::upsertErrorGroups);
        Map<String,Long> ids = new HashMap<>();
        chunks(new ArrayList<>(groups.keySet()),key -> 128,keys -> {
            for (Map<String,Object> row : mapper.errorGroupIds(keys)) ids.put(value(row,"fingerprint").toString(),((Number)value(row,"id")).longValue());
        });
        List<OccurrenceInsert> occurrences = new ArrayList<>();
        for (ErrorEvent error : errorCandidates.values()) occurrences.add(new OccurrenceInsert(ids.get(error.fingerprint()),error.occurredAt(),
                error.thread(),error.message(),error.stackTrace(),error.inferredUri(),error.associationType(),error.sourcePath(),
                error.sourceOffset(),error.eventKey(),checkpoint.instanceKey(),checkpoint.sourceId()));
        chunks(occurrences,row -> bytes(row.thread(),row.message(),row.stackTrace(),row.inferredUri(),row.associationType(),row.sourcePath(),row.eventKey(),row.instanceKey())+256,mapper::insertOccurrences);
        if (existingCheckpoint) mapper.updateCheckpoint(checkpoint);
        else mapper.insertCheckpoint(checkpoint);
    }

    @Transactional
    public void updateCheckpointOnly(Checkpoint checkpoint, boolean existing) {
        if (existing) mapper.updateCheckpoint(checkpoint);
        else mapper.insertCheckpoint(checkpoint);
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static <T> void chunks(List<T> rows, ToIntFunction<T> size, Consumer<List<T>> action) {
        List<T> chunk = new ArrayList<>(); int bytes = 0;
        for (T row : rows) {
            int estimate = size.applyAsInt(row);
            if (estimate > 1024*1024) throw new IllegalArgumentException("SQL row exceeds 1MiB parameter budget");
            if (!chunk.isEmpty() && (chunk.size() >= 500 || bytes+estimate > 1024*1024)) {
                action.accept(chunk); chunk = new ArrayList<>(); bytes = 0;
            }
            chunk.add(row); bytes += estimate;
        }
        if (!chunk.isEmpty()) action.accept(chunk);
    }
    private static int bytes(String... values) {
        int total=0; for (String value:values) if(value!=null) total+=value.getBytes(StandardCharsets.UTF_8).length+32;
        return total;
    }
    private Object value(Map<String,Object> row,String name) {
        return row.entrySet().stream().filter(e -> e.getKey().replace("_", "").equalsIgnoreCase(name.replace("_", "")))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }
    private Instant instant(Object value) {
        if(value instanceof Instant instant)return instant;
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant();
        if(value instanceof java.time.LocalDateTime date)return date.toInstant(java.time.ZoneOffset.UTC);
        if(value instanceof java.time.OffsetDateTime date)return date.toInstant();
        throw new IllegalStateException("Unsupported timestamp type");
    }

    private record AccessBucket(String service, String uri, String uriHash, Instant minute) {}
}
