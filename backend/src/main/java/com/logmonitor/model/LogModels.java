package com.logmonitor.model;

import java.time.Instant;
import java.util.List;

public final class LogModels {
    private LogModels() {}

    public record AccessEvent(String service, String uri, Instant occurredAt, String eventKey) {}

    public record ErrorEvent(
            String service,
            String category,
            String exceptionClass,
            String summary,
            Instant occurredAt,
            String thread,
            String message,
            String stackTrace,
            String inferredUri,
            String associationType,
            String sourcePath,
            long sourceOffset,
            String signature,
            String fingerprint,
            String eventKey) {}

    public record ParsedBatch(List<AccessEvent> accesses, List<ErrorEvent> errors, long parseErrors, Instant lastEventAt) {}
}
