package com.logmonitor.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "log-monitor")
public class LogMonitorProperties {
    private int scanIntervalMs = 30000;
    private int retentionDays = 180;
    private int initialLookbackDays = 180;
    private ZoneId sourceTimezone = ZoneId.of("Asia/Shanghai");
    private List<String> allowedRoots = new ArrayList<>();
    private final List<Source> sources = new ArrayList<>();
    private final Admin admin = new Admin();

    public int getScanIntervalMs() { return scanIntervalMs; }
    public void setScanIntervalMs(int scanIntervalMs) { this.scanIntervalMs = scanIntervalMs; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public int getInitialLookbackDays() { return initialLookbackDays; }
    public void setInitialLookbackDays(int initialLookbackDays) { this.initialLookbackDays = initialLookbackDays; }
    public ZoneId getSourceTimezone() { return sourceTimezone; }
    public void setSourceTimezone(ZoneId sourceTimezone) { this.sourceTimezone = sourceTimezone; }
    public List<String> getAllowedRoots() { return allowedRoots; }
    public void setAllowedRoots(List<String> allowedRoots) { this.allowedRoots = allowedRoots == null ? new ArrayList<>() : allowedRoots; }
    public List<Source> getSources() { return sources; }
    public Admin getAdmin() { return admin; }

    public static class Source {
        private Long id;
        private String name;
        private String applicationNamespace;
        private String path;
        private String realPath;
        private String realPathHash;
        private String collectorType = "LOCAL";
        private Long agentId;
        private String instanceKey = "local";
        private String validationStatus = "ACTIVE";
        private String validationError;
        private String startMode = "HISTORY_180D";
        private String namespaceMigrationStatus = "IDLE";
        private String include = "*.log";
        private String exclude = "*.error_*.log";
        private Charset charset = StandardCharsets.UTF_8;
        private List<String> uriNormalizers = new ArrayList<>();
        private java.time.Instant createdAt;
        private java.time.Instant deletedAt;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getApplicationNamespace() { return applicationNamespace == null ? name : applicationNamespace; }
        public void setApplicationNamespace(String applicationNamespace) { this.applicationNamespace = applicationNamespace; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getRealPath() { return realPath; }
        public void setRealPath(String realPath) { this.realPath = realPath; }
        public String getRealPathHash() { return realPathHash; }
        public void setRealPathHash(String realPathHash) { this.realPathHash = realPathHash; }
        public String getCollectorType() { return collectorType; }
        public void setCollectorType(String collectorType) { this.collectorType = collectorType == null ? "LOCAL" : collectorType; }
        public Long getAgentId() { return agentId; }
        public void setAgentId(Long agentId) { this.agentId = agentId; }
        public String getInstanceKey() { return instanceKey; }
        public void setInstanceKey(String instanceKey) { this.instanceKey = instanceKey == null ? "local" : instanceKey; }
        public String getValidationStatus() { return validationStatus; }
        public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
        public String getValidationError() { return validationError; }
        public void setValidationError(String validationError) { this.validationError = validationError; }
        public String getStartMode() { return startMode; }
        public void setStartMode(String startMode) { this.startMode = startMode == null ? "HISTORY_180D" : startMode; }
        public String getNamespaceMigrationStatus() { return namespaceMigrationStatus; }
        public void setNamespaceMigrationStatus(String namespaceMigrationStatus) { this.namespaceMigrationStatus = namespaceMigrationStatus == null ? "IDLE" : namespaceMigrationStatus; }
        public String getInclude() { return include; }
        public void setInclude(String include) { this.include = include; }
        public String getIncludePattern() { return include; }
        public void setIncludePattern(String include) { this.include = include; }
        public String getExclude() { return exclude; }
        public void setExclude(String exclude) { this.exclude = exclude; }
        public String getExcludePattern() { return exclude; }
        public void setExcludePattern(String exclude) { this.exclude = exclude; }
        public Charset getCharset() { return charset; }
        public void setCharset(Charset charset) { this.charset = charset == null ? StandardCharsets.UTF_8 : charset; }
        public String getCharsetName() { return charset.name(); }
        public void setCharsetName(String charsetName) { this.charset = Charset.forName(charsetName); }
        public List<String> getUriNormalizers() { return uriNormalizers; }
        public void setUriNormalizers(List<String> uriNormalizers) { this.uriNormalizers = uriNormalizers == null ? new ArrayList<>() : uriNormalizers; }
        public String getUriNormalizersText() { return String.join("\n", uriNormalizers); }
        public void setUriNormalizersText(String value) {
            this.uriNormalizers = value == null || value.isBlank() ? new ArrayList<>() : new ArrayList<>(value.lines().toList());
        }
        public java.time.Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
        public java.time.Instant getDeletedAt() { return deletedAt; }
        public void setDeletedAt(java.time.Instant deletedAt) { this.deletedAt = deletedAt; }
    }

    public static class Admin {
        private String username = "admin";
        private String password = "change-me";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

}
