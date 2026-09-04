package com.logmonitor.service;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.Checkpoint;
import com.logmonitor.mapper.LogMonitorMapper.ErrorGroupInsert;
import com.logmonitor.mapper.LogMonitorMapper.OccurrenceInsert;
import com.logmonitor.model.ApiModels.ErrorGroupRow;
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
        Map<AccessBucket, Long> accessBuckets = new LinkedHashMap<>();
        Set<String> accessEventKeys = new HashSet<>();
        for (AccessEvent access : batch.accesses()) {
            if (!accessEventKeys.add(access.eventKey()) || mapper.accessEventExists(access.eventKey()) > 0) continue;
            Instant minute = access.occurredAt().truncatedTo(ChronoUnit.MINUTES);
            String uriHash = sha256(access.uri());
            if (mapper.accessBaselineExistsBySource(checkpoint.sourceId(), uriHash, minute, access.occurredAt()) > 0) continue;
            mapper.insertAccessEvent(access.eventKey(), access.occurredAt());
            accessBuckets.merge(new AccessBucket(access.service(), access.uri(), uriHash, minute), 1L, Long::sum);
        }
        for (Map.Entry<AccessBucket, Long> entry : accessBuckets.entrySet()) {
            AccessBucket bucket = entry.getKey();
            Map<String, Object> existing = mapper.findAccess(checkpoint.sourceId(), bucket.uriHash(), bucket.minute());
            if (existing == null) {
                mapper.insertAccess(bucket.service(), checkpoint.instanceKey(), checkpoint.sourceId(), bucket.uri(), bucket.uriHash(),
                        bucket.minute(), entry.getValue());
            } else {
                mapper.incrementAccess(number(existing, "id"), entry.getValue());
            }
        }

        Map<String, List<ErrorEvent>> errorsByFingerprint = new LinkedHashMap<>();
        Set<String> errorEventKeys = new HashSet<>();
        for (ErrorEvent error : batch.errors()) {
            if (!errorEventKeys.add(error.eventKey()) || mapper.occurrenceExists(error.eventKey()) > 0) continue;
            errorsByFingerprint.computeIfAbsent(error.fingerprint(), ignored -> new ArrayList<>()).add(error);
        }
        for (List<ErrorEvent> errors : errorsByFingerprint.values()) {
            ErrorEvent representative = errors.get(0);
            Instant firstSeen = errors.stream().map(ErrorEvent::occurredAt).min(Instant::compareTo).orElseThrow();
            Instant lastSeen = errors.stream().map(ErrorEvent::occurredAt).max(Instant::compareTo).orElseThrow();
            String inferredUri = errors.stream().map(ErrorEvent::inferredUri).filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
            ErrorGroupRow group = mapper.findErrorGroupByFingerprint(representative.fingerprint());
            long groupId;
            if (group == null) {
                ErrorGroupInsert row = new ErrorGroupInsert();
                row.fingerprint = representative.fingerprint(); row.service = representative.service();
                row.signature = representative.signature();
                row.category = representative.category(); row.exceptionClass = representative.exceptionClass();
                row.summary = representative.summary(); row.firstSeen = firstSeen; row.lastSeen = lastSeen;
                row.count = errors.size(); row.inferredUri = inferredUri;
                mapper.insertErrorGroup(row);
                groupId = row.id;
            } else {
                groupId = group.id();
                mapper.incrementErrorGroup(groupId, firstSeen, lastSeen, errors.size(), inferredUri);
            }
            for (ErrorEvent error : errors) {
                mapper.insertOccurrence(new OccurrenceInsert(groupId, error.occurredAt(), error.thread(), error.message(),
                        error.stackTrace(), error.inferredUri(), error.associationType(), error.sourcePath(),
                        error.sourceOffset(), error.eventKey(), checkpoint.instanceKey(), checkpoint.sourceId()));
            }
        }
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

    private long number(Map<String, Object> row, String expected) {
        Object value = row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(expected))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing database column: " + expected));
        return ((Number) value).longValue();
    }

    private record AccessBucket(String service, String uri, String uriHash, Instant minute) {}
}
