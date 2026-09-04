package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.SourceOptions;
import com.logmonitor.model.ApiModels.BatchLogSourceRequest;
import com.logmonitor.model.ApiModels.CreateLogSourceRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogSourceService implements ApplicationRunner {
    public static final String DEFAULT_INCLUDE = "*.log";
    public static final String DEFAULT_EXCLUDE = "*.error_*.log";
    private static final String SEEDED_SETTING = "log_sources_seeded";
    private static final Pattern SOURCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Logger log = LoggerFactory.getLogger(LogSourceService.class);

    private final LogMonitorMapper mapper;
    private final LogMonitorProperties properties;
    private final AgentService agents;

    public LogSourceService(LogMonitorMapper mapper, LogMonitorProperties properties, AgentService agents) {
        this.mapper = mapper;
        this.properties = properties;
        this.agents = agents;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (mapper.appSetting(SEEDED_SETTING) == null) {
            for (Source configured : properties.getSources()) {
                seed(configured);
            }
            mapper.insertAppSetting(SEEDED_SETTING, Instant.now().toString());
        }
        synchronizeAdvancedConfiguration();
    }

    public List<Source> activeSources() {
        return mapper.activeLogSources();
    }

    public Source activeSource(long id) {
        return mapper.activeLogSource(id);
    }

    public SourceOptions options() {
        return new SourceOptions(allowedRoots().stream().map(this::canonical).map(Path::toString).toList(),
                DEFAULT_INCLUDE, DEFAULT_EXCLUDE);
    }

    public SourceOptions options(Long agentId) {
        if (agentId == null) return options();
        return new SourceOptions(agents.allowedRoots(agentId), DEFAULT_INCLUDE, DEFAULT_EXCLUDE);
    }

    @Transactional
    public Source create(String rawName, String rawPath, String rawInclude, String rawExclude) {
        return create(rawName, rawName, "LOCAL", null, rawPath, rawInclude, rawExclude, "HISTORY_180D");
    }

    @Transactional
    public Source create(String rawName, String rawCollectorType, Long agentId, String rawPath,
                         String rawInclude, String rawExclude, String rawStartMode) {
        return create(rawName, rawName, rawCollectorType, agentId, rawPath, rawInclude, rawExclude, rawStartMode);
    }

    @Transactional
    public Source create(String rawName, String rawNamespace, String rawCollectorType, Long agentId, String rawPath,
                         String rawInclude, String rawExclude, String rawStartMode) {
        return createInternal(rawName, rawNamespace, rawCollectorType, agentId, rawPath, rawInclude, rawExclude,
                rawStartMode, true);
    }

    @Transactional
    public List<Source> createBatch(BatchLogSourceRequest request) {
        if (request == null || request.sources() == null || request.sources().isEmpty()) {
            throw invalid("至少需要一个日志目录");
        }
        if (request.sources().size() > 50) throw invalid("单次最多新增 50 个日志目录");
        String collectorType = request.collectorType() == null ? "AGENT" : request.collectorType();
        List<Source> created = new ArrayList<>();
        for (CreateLogSourceRequest item : request.sources()) {
            created.add(createInternal(item.name(), item.applicationNamespace(), collectorType, request.agentId(),
                    item.path(), item.include(), item.exclude(), item.startMode(), false));
        }
        if ("AGENT".equalsIgnoreCase(collectorType) && request.agentId() != null) agents.bumpRevision(request.agentId());
        return created;
    }

    private Source createInternal(String rawName, String rawNamespace, String rawCollectorType, Long agentId, String rawPath,
                                  String rawInclude, String rawExclude, String rawStartMode, boolean bumpRevision) {
        String name = rawName == null ? "" : rawName.trim();
        if (!SOURCE_NAME.matcher(name).matches()) {
            throw invalid("日志源名称须为 1-80 位字母、数字、点、下划线或连字符");
        }
        String applicationNamespace = rawNamespace == null || rawNamespace.isBlank() ? name : rawNamespace.trim();
        if (!SOURCE_NAME.matcher(applicationNamespace).matches()) {
            throw invalid("应用命名空间须为 1-80 位字母、数字、点、下划线或连字符");
        }
        String include = valueOrDefault(rawInclude, DEFAULT_INCLUDE);
        String exclude = rawExclude == null ? DEFAULT_EXCLUDE : rawExclude.trim();
        validatePattern(include);
        if (!exclude.isBlank()) validatePattern(exclude);

        String collectorType = rawCollectorType == null || rawCollectorType.isBlank()
                ? "LOCAL" : rawCollectorType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOCAL", "AGENT").contains(collectorType)) throw invalid("采集类型无效");
        String startMode = rawStartMode == null || rawStartMode.isBlank()
                ? ("AGENT".equals(collectorType) ? "NOW" : "HISTORY_180D")
                : rawStartMode.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NOW", "HISTORY_180D").contains(startMode)) throw invalid("采集起点无效");

        Path submitted;
        Path real;
        String instanceKey;
        if ("AGENT".equals(collectorType)) {
            if (agentId == null) throw invalid("远端日志源必须选择 Agent");
            var agent = agents.requireAgent(agentId);
            submitted = validateRemotePath(rawPath, agents.allowedRoots(agentId));
            real = submitted;
            instanceKey = agent.agentUuid();
        } else {
            if (agentId != null) throw invalid("本地日志源不能绑定 Agent");
            submitted = validateManagedPath(rawPath);
            real = toRealPath(submitted);
            instanceKey = "local";
        }
        Source source = new Source();
        source.setName(name);
        source.setApplicationNamespace(applicationNamespace);
        source.setPath(submitted.toString());
        source.setRealPath(real.toString());
        source.setRealPathHash(hash(real.toString()));
        source.setInclude(include);
        source.setExclude(exclude);
        source.setCharset(StandardCharsets.UTF_8);
        source.setCollectorType(collectorType);
        source.setAgentId(agentId);
        source.setInstanceKey(instanceKey);
        source.setValidationStatus("AGENT".equals(collectorType) ? "VALIDATING" : "ACTIVE");
        source.setStartMode(startMode);
        applyConfiguredUriNormalizers(source);

        Source byPath = mapper.logSourceByPathHash(instanceKey, source.getRealPathHash());
        if (byPath != null) {
            if (byPath.getDeletedAt() != null && normalizeName(byPath.getName()).equals(normalizeName(name))
                    && normalizeName(byPath.getApplicationNamespace()).equals(normalizeName(applicationNamespace))) {
                source.setId(byPath.getId());
                source.setName(byPath.getName());
                mapper.reactivateLogSource(source);
                if (bumpRevision && agentId != null) agents.bumpRevision(agentId);
                return mapper.activeLogSource(source.getId());
            }
            throw conflict("SOURCE_PATH_EXISTS", "日志目录已被监控");
        }

        try {
            mapper.insertLogSource(source);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("SOURCE_PATH_EXISTS", "日志目录已被监控");
        }
        if (bumpRevision && agentId != null) agents.bumpRevision(agentId);
        return mapper.activeLogSource(source.getId());
    }

    @Transactional
    public Source delete(long id) {
        Source source = mapper.activeLogSource(id);
        if (source == null) {
            throw new SourceManagementException("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "日志源不存在");
        }
        mapper.deleteSourceCheckpoints(source.getId());
        if (mapper.softDeleteLogSource(id) == 0) {
            throw new SourceManagementException("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "日志源不存在");
        }
        if (source.getAgentId() != null) agents.bumpRevision(source.getAgentId());
        return source;
    }

    private void seed(Source configured) {
        try {
            String name = configured.getName() == null ? "" : configured.getName().trim();
            if (!SOURCE_NAME.matcher(name).matches()) {
                log.warn("Skipping invalid configured log source name: {}", name);
                return;
            }
            Path path = Path.of(configured.getPath()).toAbsolutePath().normalize();
            Path real = Files.exists(path) ? path.toRealPath() : path;
            if (!isWithinAllowedRoot(real)) {
                log.warn("Skipping configured log source outside allowed roots: {}", path);
                return;
            }
            Source source = copy(configured, path, real);
            Source byPath = mapper.logSourceByPathHash("local", source.getRealPathHash());
            if (byPath == null) mapper.insertLogSource(source);
        } catch (Exception exception) {
            log.warn("Could not seed configured log source {}: {}", configured.getName(), exception.getMessage());
        }
    }

    private void synchronizeAdvancedConfiguration() {
        for (Source source : mapper.activeLogSources()) {
            if (!"LOCAL".equals(source.getCollectorType())) continue;
            properties.getSources().stream()
                    .filter(configured -> normalizeName(configured.getName()).equals(normalizeName(source.getName())))
                    .findFirst()
                    .ifPresent(configured -> {
                        source.setUriNormalizers(configured.getUriNormalizers());
                        mapper.updateLogSourceAdvanced(source);
                    });
        }
    }

    private void applyConfiguredUriNormalizers(Source source) {
        properties.getSources().stream()
                .filter(configured -> normalizeName(configured.getName()).equals(normalizeName(source.getName())))
                .findFirst()
                .ifPresent(configured -> source.setUriNormalizers(configured.getUriNormalizers()));
    }

    private Source copy(Source configured, Path path, Path real) {
        Source source = new Source();
        source.setName(configured.getName().trim());
        source.setApplicationNamespace(configured.getApplicationNamespace());
        source.setPath(path.toString());
        source.setRealPath(real.toString());
        source.setRealPathHash(hash(real.toString()));
        source.setInclude(valueOrDefault(configured.getInclude(), DEFAULT_INCLUDE));
        source.setExclude(configured.getExclude() == null ? DEFAULT_EXCLUDE : configured.getExclude());
        source.setCharset(configured.getCharset());
        source.setUriNormalizers(configured.getUriNormalizers());
        source.setCollectorType("LOCAL");
        source.setInstanceKey("local");
        source.setValidationStatus("ACTIVE");
        source.setStartMode("HISTORY_180D");
        return source;
    }

    private Path validateManagedPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) throw pathError("日志目录不能为空");
        final Path path;
        try {
            path = Path.of(rawPath.trim());
        } catch (Exception exception) {
            throw pathError("日志目录格式无效");
        }
        if (!path.isAbsolute()) throw pathError("日志目录必须使用服务器绝对路径");
        for (Path part : path) {
            if ("..".equals(part.toString())) throw pathError("日志目录不能包含 .. 路径段");
        }
        Path normalized = path.normalize();
        if (!Files.isDirectory(normalized) || !Files.isReadable(normalized)) {
            throw pathError("日志目录不存在或不可读");
        }
        Path real = toRealPath(normalized);
        if (!isWithinAllowedRoot(real)) throw pathError("日志目录不在允许的挂载根目录内");
        return normalized;
    }

    private Path validateRemotePath(String rawPath, List<String> roots) {
        if (rawPath == null || rawPath.isBlank()) throw pathError("日志目录不能为空");
        final Path path;
        try {
            path = Path.of(rawPath.trim());
        } catch (Exception exception) {
            throw pathError("日志目录格式无效");
        }
        if (!path.isAbsolute()) throw pathError("日志目录必须使用服务器绝对路径");
        for (Path part : path) {
            if ("..".equals(part.toString())) throw pathError("日志目录不能包含 .. 路径段");
        }
        Path normalized = path.normalize();
        boolean allowed = roots.stream().map(Path::of).map(Path::normalize).anyMatch(normalized::startsWith);
        if (!allowed) throw pathError("日志目录不在 Agent 允许根目录内");
        return normalized;
    }

    private boolean isWithinAllowedRoot(Path real) {
        return allowedRoots().stream().anyMatch(root -> real.startsWith(canonical(root)));
    }

    private List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();
        for (String configured : properties.getAllowedRoots()) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> Path.of(value).toAbsolutePath().normalize())
                    .forEach(roots::add);
        }
        return roots;
    }

    private Path canonical(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (Exception exception) {
            return path;
        }
    }

    private Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception exception) {
            throw pathError("无法读取日志目录的真实路径");
        }
    }

    private void validatePattern(String pattern) {
        if (pattern.length() > 255) throw patternError();
        try {
            FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException exception) {
            throw patternError();
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SourceManagementException invalid(String message) {
        return new SourceManagementException("INVALID_SOURCE", HttpStatus.BAD_REQUEST, message);
    }

    private SourceManagementException pathError(String message) {
        return new SourceManagementException("SOURCE_PATH_NOT_ALLOWED", HttpStatus.BAD_REQUEST, message);
    }

    private SourceManagementException patternError() {
        return new SourceManagementException("INVALID_FILE_PATTERN", HttpStatus.BAD_REQUEST, "文件匹配规则无效");
    }

    private SourceManagementException conflict(String code, String message) {
        return new SourceManagementException(code, HttpStatus.CONFLICT, message);
    }
}
