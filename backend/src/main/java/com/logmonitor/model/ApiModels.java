package com.logmonitor.model;

import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public record PageResult<T>(List<T> items, long total, int page, int pageSize) {}
    public record SessionUser(String username, String role, boolean mustChangePassword) {}
    public record UserSummary(long id, String username, String role, boolean enabled,
                              boolean mustChangePassword, Instant createdAt) {}
    public record TimePoint(Instant time, long value) {}
    public record EndpointRow(long id, String service, String applicationNamespace, Long sourceId, String uri, long totalCount,
                              double averagePerMinute, long peakPerMinute, long errorCount) {}
    public record ErrorGroupRow(long id, String fingerprint, String service, String applicationNamespace, Long sourceId, String category,
                                String exceptionClass, String summary, Instant firstSeen,
                                Instant lastSeen, long occurrenceCount, String inferredUri) {}
    public record ErrorOccurrenceRow(long id, long groupId, Instant occurredAt, String threadName,
                                     String messageText, String stackTrace, String inferredUri,
                                     String associationType, String sourcePath, String instanceKey,
                                     String agentName) {}
    public record ErrorLogItem(long id, long groupId, String service, String applicationNamespace, String category,
                               String exceptionClass, String summary, Instant occurredAt,
                               String threadName, String inferredUri, String associationType,
                               Long sourceId, String sourceName, String instanceKey,
                               String agentName, String displayAddress) {}
    public record ErrorOccurrenceDetail(long id, long groupId, String fingerprint, String service,
                                        String applicationNamespace,
                                        String category, String exceptionClass, String summary,
                                        Instant occurredAt, String threadName, String messageText,
                                        String stackTrace, String inferredUri, String associationType,
                                        Long sourceId, String sourceName, String sourcePath, Long sourceOffset,
                                        String instanceKey, Long agentId, String agentName, String displayAddress) {}
    public record ErrorOccurrencePage(List<ErrorLogItem> items, long total, int page,
                                      int pageSize, long snapshotId) {}
    public record ErrorOccurrenceUpdates(long count, long latestId) {}
    public record SourceStatus(long id, String sourceName, String applicationNamespace, String path, String include, String exclude,
                               String status, long files, long bytesRead, long totalBytes,
                               Instant lastCollectedAt, long parseErrors, String lastError,
                               String collectorType, Long agentId, String agentName, String displayAddress, String instanceKey,
                               String startMode, String namespaceMigrationStatus) {}
    public record SourceOptions(List<String> allowedRoots, String defaultInclude, String defaultExclude) {}
    public record CreateLogSourceRequest(String name, String applicationNamespace, String collectorType, Long agentId, String path,
                                         String include, String exclude, String startMode) {}
    public record BatchLogSourceRequest(String collectorType, Long agentId, List<CreateLogSourceRequest> sources) {}
    public record NamespaceUpdateRequest(String applicationNamespace) {}
    public record ApplicationInstance(long sourceId, Long agentId, String displayAddress, String applicationName,
                                      String path, String status, boolean active, String label) {}
    public record ApplicationOption(String applicationNamespace, List<ApplicationInstance> instances) {}
    public record AgentSummary(long id, String uuid, String name, String hostName, String displayAddress, String version,
                               String status, long spoolBytes, long spoolLimitBytes, Instant lastSeenAt,
                               String lastError, List<String> allowedRoots, String createdBy, Instant createdAt) {}
    public record AgentEnrollRequest(String username, String password, String name, String hostName, String displayAddress,
                                     String version, List<AgentRootRequest> roots) {}
    public record AgentRootRequest(String path, String realPath) {}
    public record AgentEnrollResponse(long agentId, String agentUuid, String token, long configRevision,
                                      int pollIntervalSeconds, int maxBatchBytes, long spoolLimitBytes) {}
    public record AgentSourceConfig(long id, String name, String path, String include, String exclude,
                                    String charset, List<String> uriNormalizers, String startMode) {}
    public record AgentConfigResponse(long revision, List<AgentSourceConfig> sources) {}
    public record AgentSourceReport(long sourceId, String status, String realPath, String error,
                                    long files, long bytesRead, long totalBytes, long parseErrors,
                                    Instant lastCollectedAt) {}
    public record AgentHeartbeatRequest(String version, String displayAddress, long spoolBytes, long spoolLimitBytes,
                                        List<AgentSourceReport> sources) {}
    public record AgentBatchMetadata(String batchId, long sourceId, String fileKey, String generation,
                                     String path, long startOffset, long endOffset, long fileSize,
                                     Instant modifiedAt, String charset, String checksum, boolean stable) {}
    public record AgentBatchAck(String status, long expectedOffset) {}
}
