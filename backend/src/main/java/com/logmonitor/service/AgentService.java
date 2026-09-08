package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.AgentMapper;
import com.logmonitor.mapper.AgentMapper.AgentInsert;
import com.logmonitor.model.ApiModels.AgentConfigResponse;
import com.logmonitor.model.ApiModels.AgentEnrollRequest;
import com.logmonitor.model.ApiModels.AgentEnrollResponse;
import com.logmonitor.model.ApiModels.AgentHeartbeatRequest;
import com.logmonitor.model.ApiModels.AgentSourceConfig;
import com.logmonitor.model.ApiModels.AgentSummary;
import com.logmonitor.model.CollectorAgent;
import com.logmonitor.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    public static final int MAX_BATCH_BYTES = 4 * 1024 * 1024;
    public static final long SPOOL_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private final AgentMapper mapper;
    private final AuthenticationManager authenticationManager;
    private final SourceLockRegistry sourceLocks;
    private final SecureRandom random = new SecureRandom();

    public AgentService(AgentMapper mapper, AuthenticationManager authenticationManager, SourceLockRegistry sourceLocks) {
        this.mapper = mapper;
        this.authenticationManager = authenticationManager;
        this.sourceLocks = sourceLocks;
    }

    @Transactional
    public AgentEnrollResponse enroll(AgentEnrollRequest request) {
        if (request == null || request.roots() == null || request.roots().isEmpty()) {
            throw invalid("至少配置一个允许根目录");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (!NAME.matcher(name).matches()) throw invalid("Agent 名称须为 1-80 位字母、数字、点、下划线或连字符");
        var authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                request.username(), request.password()));
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        if (user.mustChangePassword()) throw new com.logmonitor.security.PasswordChangeRequiredException();
        if (mapper.activeAgentByName(name.toLowerCase(Locale.ROOT)) != null) {
            throw conflict("AGENT_NAME_EXISTS", "Agent 名称已存在");
        }
        String token = token();
        AgentInsert row = new AgentInsert();
        row.agentUuid = UUID.randomUUID().toString();
        row.name = name;
        row.hostName = required(request.hostName(), "主机名不能为空", 255);
        row.displayAddress = optionalAddress(request.displayAddress(), row.hostName);
        row.version = required(request.version(), "Agent 版本不能为空", 32);
        row.tokenHash = sha256(token);
        row.spoolLimit = SPOOL_LIMIT_BYTES;
        row.createdBy = user.getUsername();
        try {
            mapper.insertAgent(row);
            request.roots().forEach(root -> {
                String path = absolute(root.path());
                String real = absolute(root.realPath());
                mapper.insertRoot(row.id, path, real, sha256(real));
            });
        } catch (DataIntegrityViolationException exception) {
            throw conflict("AGENT_CONFLICT", "Agent 名称或允许根目录重复");
        }
        return new AgentEnrollResponse(row.id, row.agentUuid, token, 1, 10, MAX_BATCH_BYTES, SPOOL_LIMIT_BYTES);
    }

    public CollectorAgent authenticate(String token) {
        if (token == null || token.length() < 32) return null;
        return mapper.agentByTokenHash(sha256(token));
    }

    public List<AgentSummary> summaries() {
        Instant now = Instant.now();
        return mapper.activeAgents().stream().map(agent -> summary(agent, now)).toList();
    }

    public java.util.Map<Long, String> displayAddresses() {
        return mapper.allAgents().stream().collect(java.util.stream.Collectors.toMap(
                CollectorAgent::id,
                agent -> agent.displayAddress() == null || agent.displayAddress().isBlank()
                        ? agent.hostName() : agent.displayAddress()));
    }

    public AgentSummary summary(long id) {
        CollectorAgent agent = requireAgent(id);
        return summary(agent, Instant.now());
    }

    public List<String> allowedRoots(long agentId) {
        requireAgent(agentId);
        return mapper.allowedRoots(agentId);
    }

    public AgentConfigResponse configuration(long agentId) {
        CollectorAgent agent = requireAgent(agentId);
        List<AgentSourceConfig> sources = mapper.agentSources(agentId).stream().map(source ->
                new AgentSourceConfig(source.getId(), source.getName(), source.getPath(), source.getInclude(),
                        source.getExclude(), source.getCharset().name(), source.getUriNormalizers(), source.getStartMode())).toList();
        return new AgentConfigResponse(agent.configRevision(), sources);
    }

    @Transactional
    public void heartbeat(long agentId, AgentHeartbeatRequest request) {
        requireAgent(agentId);
        List<com.logmonitor.model.ApiModels.AgentSourceReport> reports = request.sources() == null ? List.of() : request.sources();
        Set<Long> sourceIds = mapper.agentSources(agentId).stream().map(Source::getId).collect(Collectors.toSet());
        for (var report : reports) {
            if (!sourceIds.contains(report.sourceId())) continue;
            String status = switch (report.status()) {
                case "ACTIVE", "ERROR", "BLOCKED", "VALIDATING" -> report.status();
                default -> "ERROR";
            };
            String real = report.realPath() == null || report.realPath().isBlank() ? null : report.realPath();
            try {
                mapper.updateSourceReport(agentId, report.sourceId(), status,
                        "ACTIVE".equals(status) ? null : abbreviate(report.error()), real,
                        real == null ? null : sha256(real));
            } catch (DataIntegrityViolationException exception) {
                mapper.updateSourceReport(agentId, report.sourceId(), "ERROR", "远端真实目录已被当前 Agent 的其他来源使用",
                        null, null);
            }
        }
        // Persisted source results include center-side validation and retain errors for omitted sources.
        String lastError = mapper.agentSources(agentId).stream().map(Source::getValidationError)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        mapper.updateHeartbeat(agentId, request.version(), optionalAddress(request.displayAddress(), null), Math.max(0, request.spoolBytes()),
                request.spoolLimitBytes() > 0 ? request.spoolLimitBytes() : SPOOL_LIMIT_BYTES, Instant.now(), lastError);
    }

    @Transactional
    public void bumpRevision(long agentId) {
        if (mapper.bumpConfigRevision(agentId) == 0) throw notFound();
    }

    @Transactional
    public CollectorAgent revoke(long id) {
        CollectorAgent agent = requireAgent(id);
        List<ReentrantLock> acquired = mapper.agentSources(id).stream().map(Source::getId).sorted()
                .map(sourceLocks::lock).toList();
        acquired.forEach(ReentrantLock::lock);
        try {
            mapper.disableAgentSources(id);
            mapper.deleteAgentCheckpoints(agent.agentUuid());
            if (mapper.revokeAgent(id) == 0) throw notFound();
            return agent;
        } finally {
            for (int index = acquired.size() - 1; index >= 0; index--) acquired.get(index).unlock();
        }
    }

    public CollectorAgent requireAgent(long id) {
        CollectorAgent agent = mapper.activeAgent(id);
        if (agent == null || !agent.enabled()) throw notFound();
        return agent;
    }

    private AgentSummary summary(CollectorAgent agent, Instant now) {
        String status;
        if (!agent.enabled()) status = "DISABLED";
        else if (agent.lastSeenAt() == null || Duration.between(agent.lastSeenAt(), now).getSeconds() > 90) status = "OFFLINE";
        else if (agent.spoolLimitBytes() > 0 && agent.spoolBytes() >= agent.spoolLimitBytes()) status = "BLOCKED";
        else if (agent.lastError() != null && !agent.lastError().isBlank()) status = "ERROR";
        else status = "ONLINE";
        return new AgentSummary(agent.id(), agent.agentUuid(), agent.name(), agent.hostName(),
                agent.displayAddress() == null || agent.displayAddress().isBlank() ? agent.hostName() : agent.displayAddress(), agent.agentVersion(),
                status, agent.spoolBytes(), agent.spoolLimitBytes(), agent.lastSeenAt(), agent.lastError(),
                mapper.allowedRoots(agent.id()), agent.createdBy(), agent.createdAt());
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String optionalAddress(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (result != null && result.length() > 255) throw invalid("Agent 展示地址不能超过 255 个字符");
        return result;
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String absolute(String value) {
        if (value == null || value.isBlank()) throw invalid("允许根目录不能为空");
        Path path = Path.of(value.trim());
        if (!path.isAbsolute()) throw invalid("允许根目录必须是绝对路径");
        return path.normalize().toString();
    }

    private String required(String value, String message, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > max) throw invalid(message);
        return result;
    }

    private String abbreviate(String value) {
        return value == null ? null : value.substring(0, Math.min(2000, value.length()));
    }

    private SourceManagementException invalid(String message) {
        return new SourceManagementException("INVALID_AGENT", HttpStatus.BAD_REQUEST, message);
    }

    private SourceManagementException conflict(String code, String message) {
        return new SourceManagementException(code, HttpStatus.CONFLICT, message);
    }

    private SourceManagementException notFound() {
        return new SourceManagementException("AGENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Agent 不存在或已撤销");
    }
}
