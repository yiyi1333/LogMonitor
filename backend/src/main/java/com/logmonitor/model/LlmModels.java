package com.logmonitor.model;

import java.time.Instant;
import java.util.List;

public final class LlmModels {
    private LlmModels() {}

    public record ProviderRow(Long id, String name, String normalizedName, String providerType,
                              String protocolType, String baseUrl, String apiKeyCiphertext,
                              String apiKeyNonce, int keyVersion, boolean enabled,
                              String lastTestStatus, String lastTestError, Instant lastTestedAt,
                              String createdBy, Instant createdAt, Instant updatedAt) {}

    public record ModelRow(Long id, long providerId, String modelId, String normalizedModelId,
                           String displayName, boolean enabled, Instant createdAt, Instant updatedAt) {}

    public record ModelOption(long id, long providerId, String providerName, String providerType,
                              String modelId, String displayName, boolean enabled) {}

    public record ProviderView(long id, String name, String providerType, String protocolType,
                               String baseUrl, boolean apiKeyConfigured, boolean enabled,
                               String lastTestStatus, String lastTestError, Instant lastTestedAt,
                               String createdBy, Instant createdAt, List<ModelOption> models) {}

    public record SettingsView(Long selectedModelId, Long defaultModelId, ModelOption effectiveModel,
                               boolean fallbackApplied, List<ModelOption> models) {}

    public record StructuredAnalysis(String overview, List<String> rootCauses,
                                     List<String> investigationSteps, List<String> fixSuggestions,
                                     String riskLevel) {}

    public record AnalysisRow(Long id, long groupId, Long providerConfigId, Long modelConfigId,
                              String providerName, String providerType, String modelName,
                              String promptVersion, String locale, String requestedBy, String status,
                              String resultJson, String resultText, Long promptTokens,
                              Long completionTokens, String failureReason,
                              Instant createdAt, Instant updatedAt) {}

    public record AnalysisView(Long id, long groupId, Long modelConfigId, String providerName,
                               String providerType, String modelName, String promptVersion,
                               String locale, String requestedBy, String status, StructuredAnalysis result,
                               String resultText, Long promptTokens, Long completionTokens,
                               String failureReason, Instant createdAt, Instant updatedAt) {}

    public record OccurrenceAnalysisRow(Long id, long occurrenceId, Long providerConfigId,
                                        Long modelConfigId, String providerName, String providerType,
                                        String modelName, String promptVersion, String locale,
                                        String requestedBy, String status, String resultJson,
                                        String resultText, Long promptTokens, Long completionTokens,
                                        String failureReason, Instant createdAt, Instant updatedAt) {}

    public record OccurrenceAnalysisView(Long id, long occurrenceId, Long modelConfigId,
                                         String providerName, String providerType, String modelName,
                                         String promptVersion, String locale, String requestedBy,
                                         String status, StructuredAnalysis result, String resultText,
                                         Long promptTokens, Long completionTokens, String failureReason,
                                         Instant createdAt, Instant updatedAt) {}

    public record Connection(long providerId, long modelConfigId, String providerName,
                             String providerType, String protocolType, String baseUrl,
                             String apiKey, String modelId) {}

    public record Completion(String content, Long promptTokens, Long completionTokens) {}
    public record ConnectionTestView(boolean success, long latencyMs, String message) {}
    public record DiscoveredModel(String modelId, String displayName, String owner,
                                  String capabilityStatus, boolean alreadyConfigured) {}
    public record ModelDiscoveryView(String source, String catalogVersion, String warning,
                                     List<DiscoveredModel> models) {}
    public record ModelImportView(List<ModelOption> created, List<String> skippedModelIds) {}

    public static final class ProviderInsert {
        private Long id;
        private String name;
        private String normalizedName;
        private String providerType;
        private String protocolType;
        private String baseUrl;
        private String apiKeyCiphertext;
        private String apiKeyNonce;
        private boolean enabled;
        private String createdBy;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getNormalizedName() { return normalizedName; }
        public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
        public String getProviderType() { return providerType; }
        public void setProviderType(String providerType) { this.providerType = providerType; }
        public String getProtocolType() { return protocolType; }
        public void setProtocolType(String protocolType) { this.protocolType = protocolType; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKeyCiphertext() { return apiKeyCiphertext; }
        public void setApiKeyCiphertext(String apiKeyCiphertext) { this.apiKeyCiphertext = apiKeyCiphertext; }
        public String getApiKeyNonce() { return apiKeyNonce; }
        public void setApiKeyNonce(String apiKeyNonce) { this.apiKeyNonce = apiKeyNonce; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    }

    public static final class ModelInsert {
        private Long id;
        private long providerId;
        private String modelId;
        private String normalizedModelId;
        private String displayName;
        private boolean enabled;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getProviderId() { return providerId; }
        public void setProviderId(long providerId) { this.providerId = providerId; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getNormalizedModelId() { return normalizedModelId; }
        public void setNormalizedModelId(String normalizedModelId) { this.normalizedModelId = normalizedModelId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
