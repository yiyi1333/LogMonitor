package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.EndpointRow;
import com.logmonitor.model.ApiModels.ErrorGroupRow;
import com.logmonitor.model.ApiModels.PageResult;
import com.logmonitor.model.ApiModels.SourceStatus;
import com.logmonitor.model.ApiModels.TimePoint;
import com.logmonitor.model.ApiModels.ApplicationInstance;
import com.logmonitor.model.ApiModels.ApplicationOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
    private final LogMonitorMapper mapper;
    private final LogMonitorProperties properties;
    private final LogSourceService sources;
    private final AgentService agents;
    public QueryService(LogMonitorMapper mapper, LogMonitorProperties properties, LogSourceService sources,
                        AgentService agents) {
        this.mapper = mapper;
        this.properties = properties;
        this.sources = sources;
        this.agents = agents;
    }

    public Map<String, Object> dashboard(Instant from, Instant to, String service) {
        return dashboard(from, to, service, null);
    }

    public Map<String, Object> dashboard(Instant from, Instant to, String service, Long agentId) {
        return dashboard(from, to, service, null, agentId);
    }

    public Map<String, Object> dashboard(Instant from, Instant to, String applicationNamespace, Long sourceId, Long agentId) {
        validateRange(from, to);
        Selection selection = selection(applicationNamespace, sourceId, agentId);
        String instanceKey = selection.instanceKey();
        String service = selection.namespace();
        Map<String, Object> totals = mapper.dashboardTotals(from, to, service, instanceKey, selection.sourceId());
        long totalAccess = number(totals, "totalAccess", "total_access");
        long minutes = Math.max(1, Duration.between(from, to).toMinutes() + 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalAccess", totalAccess);
        result.put("averagePerMinute", BigDecimal.valueOf((double) totalAccess / minutes).setScale(2, RoundingMode.HALF_UP));
        result.put("peakPerMinute", number(totals, "peakPerMinute", "peak_per_minute"));
        result.put("systemErrors", number(totals, "systemErrors", "system_errors"));
        result.put("businessErrors", number(totals, "businessErrors", "business_errors"));
        result.put("accessTrend", bucket(mapper.accessTrend(from, to, service, instanceKey, selection.sourceId()), from, to));
        result.put("errorTrend", bucket(mapper.errorTrendRaw(from, to, service, instanceKey, selection.sourceId()), from, to));
        result.put("topEndpoints", mapper.endpoints(from, to, service, instanceKey, selection.sourceId(), null, 6, 0));
        result.put("topErrors", mapper.topErrorGroups(from, to, service, instanceKey, selection.sourceId(), 6));
        result.put("services", mapper.services());
        return result;
    }

    public PageResult<EndpointRow> endpoints(Instant from, Instant to, String service, String keyword, int page, int pageSize) {
        return endpoints(from, to, service, null, keyword, page, pageSize);
    }

    public PageResult<EndpointRow> endpoints(Instant from, Instant to, String service, Long agentId,
                                             String keyword, int page, int pageSize) {
        return endpoints(from, to, service, null, agentId, keyword, page, pageSize);
    }

    public PageResult<EndpointRow> endpoints(Instant from, Instant to, String applicationNamespace, Long sourceId,
                                             Long agentId, String keyword, int page, int pageSize) {
        validatePage(page, pageSize); validateRange(from, to);
        Selection selection = selection(applicationNamespace, sourceId, agentId);
        return new PageResult<>(mapper.endpoints(from, to, selection.namespace(), selection.instanceKey(), selection.sourceId(),
                blankToNull(keyword), pageSize, (page - 1) * pageSize),
                mapper.endpointCount(from, to, selection.namespace(), selection.instanceKey(), selection.sourceId(), blankToNull(keyword)), page, pageSize);
    }

    public List<TimePoint> endpointTrend(long id, Instant from, Instant to) {
        return endpointTrend(id, from, to, null);
    }

    public List<TimePoint> endpointTrend(long id, Instant from, Instant to, Long agentId) {
        return endpointTrend(id, from, to, null, agentId);
    }

    public List<TimePoint> endpointTrend(long id, Instant from, Instant to, Long sourceId, Long agentId) {
        validateRange(from, to);
        String applicationNamespace = mapper.endpointNamespace(id);
        String applicationName = mapper.endpointApplicationName(id);
        String uri = mapper.endpointUri(id);
        if (applicationNamespace == null || applicationName == null || uri == null) throw new IllegalArgumentException("接口不存在");
        Selection selection = selection(applicationNamespace, sourceId, agentId);
        return bucket(mapper.endpointTrend(applicationNamespace, applicationName, uri, from, to,
                selection.instanceKey(), selection.sourceId()), from, to);
    }

    public PageResult<ErrorGroupRow> errors(Instant from, Instant to, String service, String category, String keyword, String endpoint, int page, int pageSize) {
        return errors(from, to, service, null, category, keyword, endpoint, page, pageSize);
    }

    public PageResult<ErrorGroupRow> errors(Instant from, Instant to, String service, Long agentId,
                                            String category, String keyword, String endpoint, int page, int pageSize) {
        return errors(from, to, service, null, agentId, category, keyword, endpoint, page, pageSize);
    }

    public PageResult<ErrorGroupRow> errors(Instant from, Instant to, String applicationNamespace, Long sourceId, Long agentId,
                                            String category, String keyword, String endpoint, int page, int pageSize) {
        validatePage(page, pageSize); validateRange(from, to);
        Selection selection = selection(applicationNamespace, sourceId, agentId);
        List<ErrorGroupRow> items = mapper.errorGroups(from, to, selection.namespace(), selection.instanceKey(), selection.sourceId(),
                blankToNull(category), blankToNull(keyword), blankToNull(endpoint), pageSize, (page - 1) * pageSize);
        long total = mapper.errorGroupCount(from, to, selection.namespace(), selection.instanceKey(), selection.sourceId(),
                blankToNull(category), blankToNull(keyword), blankToNull(endpoint));
        return new PageResult<>(items, total, page, pageSize);
    }

    public List<SourceStatus> sourceStatuses() {
        List<Map<String, Object>> rows = mapper.checkpoints();
        Map<Long, com.logmonitor.model.ApiModels.AgentSummary> agentRows = agents.summaries().stream()
                .collect(java.util.stream.Collectors.toMap(com.logmonitor.model.ApiModels.AgentSummary::id, value -> value));
        List<SourceStatus> result = new ArrayList<>();
        for (var source : sources.activeSources()) {
            List<Map<String, Object>> selected = rows.stream()
                    .filter(r -> source.getId() == number(r, "sourceId", "source_id"))
                    .toList();
            long total = selected.stream().mapToLong(r -> number(r,"fileSize","file_size")).sum();
            long read = selected.stream().mapToLong(r -> number(r,"byteOffset","byte_offset")).sum();
            long errors = selected.stream().mapToLong(r -> number(r,"parseErrorCount","parse_error_count")).sum();
            Instant last = selected.stream().map(r -> instant(r,"lastCollectedAt","last_collected_at")).filter(v -> v != null).max(Comparator.naturalOrder()).orElse(null);
            var agent = source.getAgentId() == null ? null : agentRows.get(source.getAgentId());
            boolean unavailable = "LOCAL".equals(source.getCollectorType())
                    && (!Files.isDirectory(Path.of(source.getRealPath())) || !Files.isReadable(Path.of(source.getRealPath())));
            String status;
            if (!"ACTIVE".equals(source.getValidationStatus())) status = source.getValidationStatus();
            else if (agent != null && "OFFLINE".equals(agent.status())) status = "OFFLINE";
            else if (unavailable || selected.stream().anyMatch(r -> "ERROR".equals(value(r,"status")))) status = "ERROR";
            else if ("AGENT".equals(source.getCollectorType()) && agent != null && "ONLINE".equals(agent.status())) status = "ACTIVE";
            else status = selected.isEmpty() ? "WAITING" : "ACTIVE";
            String lastError = selected.stream().map(r -> value(r,"lastError","last_error")).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
            if (source.getValidationError() != null && !source.getValidationError().isBlank()) lastError = source.getValidationError();
            if (unavailable) lastError = "日志目录不存在或不可读";
            result.add(new SourceStatus(source.getId(), source.getName(), source.getApplicationNamespace(), source.getPath(), source.getInclude(), source.getExclude(), status,
                    selected.size(), read, total, last, errors, lastError, source.getCollectorType(), source.getAgentId(),
                    agent == null ? null : agent.name(), agent == null ? "local" : agent.displayAddress(), source.getInstanceKey(),
                    source.getStartMode(), source.getNamespaceMigrationStatus()));
        }
        return result;
    }

    public List<ApplicationOption> applicationOptions() {
        Map<Long, String> addresses = agents.displayAddresses();
        Map<String, List<ApplicationInstance>> grouped = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var source : sources.activeSources()) {
            String address = source.getAgentId() == null ? "local"
                    : addresses.getOrDefault(source.getAgentId(), source.getInstanceKey());
            String status = source.getNamespaceMigrationStatus().equals("IDLE")
                    ? source.getValidationStatus() : source.getNamespaceMigrationStatus();
            String label = address + "：" + source.getName() + "（" + source.getPath() + "）";
            grouped.computeIfAbsent(source.getApplicationNamespace(), ignored -> new ArrayList<>())
                    .add(new ApplicationInstance(source.getId(), source.getAgentId(), address, source.getName(),
                            source.getPath(), status, true, label));
        }
        return grouped.entrySet().stream().map(entry -> new ApplicationOption(entry.getKey(), entry.getValue())).toList();
    }

    public SourceStatus sourceStatus(long id) {
        return sourceStatuses().stream().filter(source -> source.id() == id).findFirst()
                .orElseThrow(() -> new SourceManagementException("SOURCE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND, "日志源不存在"));
    }

    private List<TimePoint> bucket(List<Map<String, Object>> rows, Instant from, Instant to) {
        boolean hourly = Duration.between(from, to).toHours() > 48;
        Map<Instant, Long> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Instant time = instant(row, "time");
            if (time == null) continue;
            Instant key = hourly ? time.truncatedTo(ChronoUnit.HOURS) : time.truncatedTo(ChronoUnit.MINUTES);
            buckets.merge(key, number(row, "metricValue", "metric_value"), Long::sum);
        }
        return buckets.entrySet().stream().map(e -> new TimePoint(e.getKey(), e.getValue())).toList();
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) throw new IllegalArgumentException("时间范围无效");
        if (Duration.between(from, to).toDays() > properties.getRetentionDays()) throw new IllegalArgumentException("查询范围不能超过180天");
    }
    private void validatePage(int page, int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String instanceKey(Long agentId) { return agentId == null ? null : agents.requireAgent(agentId).agentUuid(); }
    public String agentInstanceKey(Long agentId) { return instanceKey(agentId); }
    public Selection selection(String applicationNamespace, Long sourceId, Long agentId) {
        String namespace = blankToNull(applicationNamespace);
        String key = instanceKey(agentId);
        if (sourceId != null) {
            var source = mapper.logSource(sourceId);
            if (source == null) throw new SourceManagementException("SOURCE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND, "日志源不存在");
            if (namespace != null && !source.getApplicationNamespace().equalsIgnoreCase(namespace)) {
                throw new SourceManagementException("SOURCE_NAMESPACE_MISMATCH", org.springframework.http.HttpStatus.BAD_REQUEST,
                        "日志源不属于指定应用命名空间");
            }
            if (key != null && !source.getInstanceKey().equals(key)) {
                throw new SourceManagementException("SOURCE_AGENT_MISMATCH", org.springframework.http.HttpStatus.BAD_REQUEST,
                        "日志源不属于指定 Agent");
            }
            namespace = source.getApplicationNamespace();
        }
        return new Selection(namespace, sourceId, key);
    }
    public record Selection(String namespace, Long sourceId, String instanceKey) {}
    private long number(Map<String,Object> row, String... keys) { Object v = raw(row, keys); return v == null ? 0 : ((Number)v).longValue(); }
    private String value(Map<String,Object> row, String... keys) { Object v = raw(row,keys); return v == null ? null : v.toString(); }
    private Instant instant(Map<String,Object> row, String... keys) {
        Object v = raw(row, keys);
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp t) return t.toInstant();
        if (v instanceof java.time.LocalDateTime dt) return dt.toInstant(java.time.ZoneOffset.UTC);
        return v == null ? null : Instant.parse(v.toString());
    }
    private Object raw(Map<String,Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) return row.get(key);
            for (var entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }
}
