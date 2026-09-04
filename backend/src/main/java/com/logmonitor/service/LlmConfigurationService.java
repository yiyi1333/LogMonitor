package com.logmonitor.service;

import com.logmonitor.config.LlmProperties;
import com.logmonitor.mapper.LlmMapper;
import com.logmonitor.model.LlmModels.Connection;
import com.logmonitor.model.LlmModels.ConnectionTestView;
import com.logmonitor.model.LlmModels.DiscoveredModel;
import com.logmonitor.model.LlmModels.ModelDiscoveryView;
import com.logmonitor.model.LlmModels.ModelImportView;
import com.logmonitor.model.LlmModels.ModelInsert;
import com.logmonitor.model.LlmModels.ModelOption;
import com.logmonitor.model.LlmModels.ModelRow;
import com.logmonitor.model.LlmModels.ProviderInsert;
import com.logmonitor.model.LlmModels.ProviderRow;
import com.logmonitor.model.LlmModels.ProviderView;
import com.logmonitor.model.LlmModels.SettingsView;
import com.logmonitor.service.LlmSecretService.EncryptedSecret;
import com.logmonitor.service.LlmModelDiscoveryService.DiscoveryConnection;
import com.logmonitor.service.LlmModelDiscoveryService.DiscoveryResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LlmConfigurationService {
    private static final Set<String> PROVIDER_TYPES = Set.of(
            "DEEPSEEK", "OPENAI", "QWEN", "KIMI", "MINIMAX", "ZHIPU",
            "OPENAI_COMPATIBLE", "ANTHROPIC", "GEMINI");
    private final LlmMapper mapper;
    private final LlmSecretService secrets;
    private final LlmClientService client;
    private final LlmModelDiscoveryService discovery;
    private final LlmProperties properties;

    public LlmConfigurationService(LlmMapper mapper, LlmSecretService secrets,
                                   LlmClientService client, LlmModelDiscoveryService discovery,
                                   LlmProperties properties) {
        this.mapper = mapper;
        this.secrets = secrets;
        this.client = client;
        this.discovery = discovery;
        this.properties = properties;
    }

    public List<ProviderView> providers() {
        return mapper.providers().stream().map(this::view).toList();
    }

    @Transactional
    public ProviderView createProvider(ProviderCommand command, String username) {
        ValidatedProvider validated = validateProvider(command);
        if (command.apiKey() == null || command.apiKey().isBlank()) {
            throw badRequest("LLM_API_KEY_REQUIRED", "API Key 不能为空");
        }
        if (command.models() == null || command.models().isEmpty()) {
            throw badRequest("LLM_MODEL_REQUIRED", "至少配置一个模型");
        }
        EncryptedSecret secret = secrets.encrypt(command.apiKey());
        ProviderInsert insert = new ProviderInsert();
        insert.setName(validated.name());
        insert.setNormalizedName(validated.normalizedName());
        insert.setProviderType(validated.providerType());
        insert.setProtocolType(validated.protocolType());
        insert.setBaseUrl(validated.baseUrl());
        insert.setApiKeyCiphertext(secret.ciphertext());
        insert.setApiKeyNonce(secret.nonce());
        insert.setEnabled(command.enabled());
        insert.setCreatedBy(username);
        try {
            mapper.insertProvider(insert);
            for (ModelCommand model : command.models()) insertModel(insert.getId(), model);
        } catch (DuplicateKeyException exception) {
            throw conflict("LLM_CONFIG_EXISTS", "供应商名称或模型 ID 已存在");
        }
        ensureDefault();
        return view(requireProvider(insert.getId()));
    }

    @Transactional
    public ProviderView updateProvider(long id, ProviderCommand command) {
        requireProvider(id);
        ValidatedProvider validated = validateProvider(command);
        EncryptedSecret secret = command.apiKey() == null || command.apiKey().isBlank()
                ? null : secrets.encrypt(command.apiKey());
        try {
            mapper.updateProvider(id, validated.name(), validated.normalizedName(), validated.providerType(),
                    validated.protocolType(), validated.baseUrl(),
                    secret == null ? null : secret.ciphertext(), secret == null ? null : secret.nonce());
        } catch (DuplicateKeyException exception) {
            throw conflict("LLM_CONFIG_EXISTS", "供应商名称已存在");
        }
        return view(requireProvider(id));
    }

    @Transactional
    public ProviderView setProviderEnabled(long id, boolean enabled) {
        requireProvider(id);
        mapper.updateProviderStatus(id, enabled);
        ensureDefault();
        return view(requireProvider(id));
    }

    @Transactional
    public void deleteProvider(long id) {
        requireProvider(id);
        if (mapper.deleteProvider(id) == 0) throw notFound("供应商配置不存在");
        ensureDefault();
    }

    @Transactional
    public ModelOption addModel(long providerId, ModelCommand command) {
        ProviderRow provider = requireProvider(providerId);
        ModelInsert insert;
        try {
            insert = insertModel(providerId, command);
        } catch (DuplicateKeyException exception) {
            throw conflict("LLM_MODEL_EXISTS", "模型 ID 已存在");
        }
        ensureDefault();
        return option(provider, requireModel(insert.getId()), insert.isEnabled());
    }

    @Transactional
    public ModelOption updateModel(long providerId, long modelId, ModelCommand command) {
        ProviderRow provider = requireProvider(providerId);
        ModelValues values = validateModel(command);
        try {
            if (mapper.updateModel(providerId, modelId, values.modelId(), values.normalizedModelId(),
                    values.displayName()) == 0) throw notFound("模型配置不存在");
        } catch (DuplicateKeyException exception) {
            throw conflict("LLM_MODEL_EXISTS", "模型 ID 已存在");
        }
        ModelRow model = requireModel(modelId);
        return option(provider, model, model.enabled());
    }

    @Transactional
    public ModelOption setModelEnabled(long providerId, long modelId, boolean enabled) {
        ProviderRow provider = requireProvider(providerId);
        if (mapper.updateModelStatus(providerId, modelId, enabled) == 0) throw notFound("模型配置不存在");
        ensureDefault();
        ModelRow model = requireModel(modelId);
        return option(provider, model, model.enabled());
    }

    @Transactional
    public void deleteModel(long providerId, long modelId) {
        requireProvider(providerId);
        if (mapper.deleteModel(providerId, modelId) == 0) throw notFound("模型配置不存在");
        ensureDefault();
    }

    public SettingsView settings(long userId) {
        List<ModelOption> options = mapper.modelOptions().stream().filter(ModelOption::enabled).toList();
        Long selected = mapper.preference(userId);
        ModelOption effective = selected == null ? null : mapper.availableModel(selected);
        boolean fallback = selected != null && effective == null;
        if (effective == null) {
            Long defaultId = mapper.defaultModelId();
            effective = defaultId == null ? null : mapper.availableModel(defaultId);
        }
        if (effective == null) effective = mapper.firstAvailableModel();
        return new SettingsView(selected, mapper.defaultModelId(), effective, fallback, options);
    }

    @Transactional
    public SettingsView setPreference(long userId, long modelId) {
        if (mapper.availableModel(modelId) == null) {
            throw conflict("LLM_MODEL_UNAVAILABLE", "模型不存在或已停用");
        }
        if (mapper.preferenceCount(userId) == 0) mapper.insertPreference(userId, modelId);
        else mapper.updatePreference(userId, modelId);
        return settings(userId);
    }

    @Transactional
    public ModelOption setDefault(long modelId) {
        ModelOption option = mapper.availableModel(modelId);
        if (option == null) throw conflict("LLM_MODEL_UNAVAILABLE", "模型不存在或已停用");
        mapper.setDefaultModel(modelId);
        return option;
    }

    public Connection resolveConnection(long userId) {
        SettingsView settings = settings(userId);
        if (settings.effectiveModel() == null) {
            throw conflict("LLM_MODEL_NOT_CONFIGURED", "尚未配置可用的 LLM 模型");
        }
        return connection(settings.effectiveModel().id());
    }

    public Connection connection(long modelId) {
        ModelRow model = requireModel(modelId);
        ProviderRow provider = requireProvider(model.providerId());
        if (!model.enabled() || !provider.enabled()) {
            throw conflict("LLM_MODEL_UNAVAILABLE", "模型不存在或已停用");
        }
        return new Connection(provider.id(), model.id(), provider.name(), provider.providerType(),
                provider.protocolType(), provider.baseUrl(),
                secrets.decrypt(provider.apiKeyCiphertext(), provider.apiKeyNonce()), model.modelId());
    }

    public ConnectionTestView test(ConnectionTestCommand command) {
        long started = System.nanoTime();
        Long persistedProviderId = command.providerId();
        String rawKey = command.apiKey();
        String keyForRedaction = rawKey;
        try {
            Connection connection;
            if (persistedProviderId != null) {
                if (command.modelConfigId() == null) throw badRequest("LLM_MODEL_REQUIRED", "请选择测试模型");
                ModelRow model = requireModel(command.modelConfigId());
                ProviderRow provider = requireProvider(persistedProviderId);
                if (model.providerId() != provider.id()) throw notFound("模型配置不存在");
                String key = rawKey == null || rawKey.isBlank()
                        ? secrets.decrypt(provider.apiKeyCiphertext(), provider.apiKeyNonce()) : rawKey;
                keyForRedaction = key;
                connection = new Connection(provider.id(), model.id(), provider.name(), provider.providerType(),
                        provider.protocolType(), provider.baseUrl(), key, model.modelId());
            } else {
                ValidatedProvider provider = validateProvider(new ProviderCommand(command.name(), command.providerType(),
                        command.baseUrl(), command.apiKey(), true, List.of()));
                if (rawKey == null || rawKey.isBlank()) throw badRequest("LLM_API_KEY_REQUIRED", "API Key 不能为空");
                ModelValues model = validateModel(new ModelCommand(command.modelId(), command.modelId(), true));
                connection = new Connection(0, 0, provider.name(), provider.providerType(), provider.protocolType(),
                        provider.baseUrl(), rawKey, model.modelId());
            }
            client.complete(connection, "Return valid JSON only.",
                    "Return {\"overview\":\"OK\",\"rootCauses\":[],\"investigationSteps\":[]," +
                            "\"fixSuggestions\":[],\"riskLevel\":\"LOW\"}.", true);
            if (persistedProviderId != null) mapper.updateProviderTest(persistedProviderId, "SUCCESS", null);
            return new ConnectionTestView(true, elapsed(started), "连接成功");
        } catch (RuntimeException exception) {
            String message = safeMessage(exception, keyForRedaction);
            if (persistedProviderId != null && mapper.provider(persistedProviderId) != null) {
                mapper.updateProviderTest(persistedProviderId, "FAILED", message);
            }
            return new ConnectionTestView(false, elapsed(started), message);
        }
    }

    public ModelDiscoveryView discover(DiscoveryCommand command) {
        ValidatedProvider provider = validateProvider(new ProviderCommand("model-discovery",
                command.providerType(), command.baseUrl(), command.apiKey(), true, List.of()));
        String apiKey = command.apiKey();
        requireDiscoveryKey(provider.providerType(), apiKey);
        return discovered(provider, apiKey, Set.of());
    }

    public ModelDiscoveryView discover(long providerId, SavedDiscoveryCommand command) {
        ProviderRow stored = requireProvider(providerId);
        String baseUrl = command != null && command.baseUrl() != null && !command.baseUrl().isBlank()
                ? command.baseUrl() : stored.baseUrl();
        ValidatedProvider provider = validateProvider(new ProviderCommand(stored.name(), stored.providerType(),
                baseUrl, command == null ? null : command.apiKey(), stored.enabled(), List.of()));
        String suppliedKey = command == null ? null : command.apiKey();
        String apiKey = suppliedKey == null || suppliedKey.isBlank()
                ? secrets.decrypt(stored.apiKeyCiphertext(), stored.apiKeyNonce()) : suppliedKey;
        requireDiscoveryKey(provider.providerType(), apiKey);
        Set<String> configured = mapper.models(providerId).stream()
                .map(ModelRow::normalizedModelId).collect(java.util.stream.Collectors.toSet());
        return discovered(provider, apiKey, configured);
    }

    @Transactional
    public ModelImportView importModels(long providerId, List<ModelCommand> commands) {
        ProviderRow provider = requireProvider(providerId);
        if (commands == null || commands.isEmpty()) {
            throw badRequest("LLM_MODEL_IMPORT_EMPTY", "请选择至少一个要导入的模型");
        }
        if (commands.size() > 1000) {
            throw badRequest("INVALID_LLM_MODEL", "单次最多导入 1000 个模型");
        }
        List<ModelOption> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> existing = mapper.models(providerId).stream()
                .map(ModelRow::normalizedModelId).collect(java.util.stream.Collectors.toSet());
        for (ModelCommand command : commands) {
            ModelValues values = validateModel(command);
            if (!seen.add(values.normalizedModelId())
                    || existing.contains(values.normalizedModelId())) {
                skipped.add(values.modelId());
                continue;
            }
            ModelInsert insert = insertModel(providerId,
                    new ModelCommand(values.modelId(), values.displayName(), true));
            created.add(option(provider, requireModel(insert.getId()), true));
            existing.add(values.normalizedModelId());
        }
        ensureDefault();
        return new ModelImportView(created, skipped);
    }

    private ModelDiscoveryView discovered(ValidatedProvider provider, String apiKey, Set<String> configured) {
        DiscoveryResult result = discovery.discover(new DiscoveryConnection(provider.providerType(),
                provider.protocolType(), provider.baseUrl(), apiKey));
        List<DiscoveredModel> models = result.models().stream().map(model -> new DiscoveredModel(
                model.modelId(), model.displayName(), model.owner(), model.capabilityStatus(),
                configured.contains(model.modelId().toLowerCase(Locale.ROOT)))).toList();
        return new ModelDiscoveryView(result.source(), result.catalogVersion(), result.warning(), models);
    }

    private void requireDiscoveryKey(String providerType, String apiKey) {
        if (!Set.of("QWEN", "ZHIPU").contains(providerType)
                && (apiKey == null || apiKey.isBlank())) {
            throw badRequest("LLM_API_KEY_REQUIRED", "API Key 不能为空");
        }
    }

    private ProviderView view(ProviderRow provider) {
        List<ModelOption> models = mapper.models(provider.id()).stream()
                .map(model -> option(provider, model, model.enabled())).toList();
        return new ProviderView(provider.id(), provider.name(), provider.providerType(), provider.protocolType(),
                provider.baseUrl(), provider.apiKeyCiphertext() != null, provider.enabled(),
                provider.lastTestStatus(), provider.lastTestError(), provider.lastTestedAt(),
                provider.createdBy(), provider.createdAt(), models);
    }

    private ModelOption option(ProviderRow provider, ModelRow model, boolean enabled) {
        return new ModelOption(model.id(), provider.id(), provider.name(), provider.providerType(),
                model.modelId(), model.displayName(), enabled);
    }

    private ModelInsert insertModel(long providerId, ModelCommand command) {
        ModelValues values = validateModel(command);
        ModelInsert insert = new ModelInsert();
        insert.setProviderId(providerId);
        insert.setModelId(values.modelId());
        insert.setNormalizedModelId(values.normalizedModelId());
        insert.setDisplayName(values.displayName());
        insert.setEnabled(command.enabled());
        mapper.insertModel(insert);
        return insert;
    }

    private ValidatedProvider validateProvider(ProviderCommand command) {
        String name = command.name() == null ? "" : command.name().trim();
        if (name.isEmpty() || name.length() > 80) throw badRequest("INVALID_LLM_CONFIG", "供应商名称须为 1-80 个字符");
        String type = command.providerType() == null ? "" : command.providerType().trim().toUpperCase(Locale.ROOT);
        if (!PROVIDER_TYPES.contains(type)) throw badRequest("INVALID_LLM_CONFIG", "不支持的供应商类型");
        String protocol = switch (type) {
            case "ANTHROPIC" -> "ANTHROPIC";
            case "GEMINI" -> "GEMINI";
            default -> "OPENAI_COMPATIBLE";
        };
        String baseUrl = defaultBaseUrl(type, command.baseUrl());
        validateBaseUrl(baseUrl);
        return new ValidatedProvider(name, name.toLowerCase(Locale.ROOT), type, protocol,
                baseUrl.replaceAll("/+$", ""));
    }

    private ModelValues validateModel(ModelCommand command) {
        String modelId = command.modelId() == null ? "" : command.modelId().trim();
        if (modelId.isEmpty() || modelId.length() > 160) throw badRequest("INVALID_LLM_MODEL", "模型 ID 须为 1-160 个字符");
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? modelId : command.displayName().trim();
        if (displayName.length() > 160) throw badRequest("INVALID_LLM_MODEL", "模型显示名称不能超过 160 个字符");
        return new ModelValues(modelId, modelId.toLowerCase(Locale.ROOT), displayName);
    }

    private String defaultBaseUrl(String type, String supplied) {
        if (supplied != null && !supplied.isBlank()) return supplied.trim();
        return switch (type) {
            case "DEEPSEEK" -> "https://api.deepseek.com";
            case "OPENAI" -> "https://api.openai.com/v1";
            case "QWEN" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "KIMI" -> "https://api.moonshot.ai/v1";
            case "MINIMAX" -> "https://api.minimaxi.com/v1";
            case "ZHIPU" -> "https://open.bigmodel.cn/api/paas/v4";
            case "ANTHROPIC" -> "https://api.anthropic.com";
            case "GEMINI" -> "https://generativelanguage.googleapis.com/v1beta";
            default -> throw badRequest("INVALID_LLM_CONFIG", "自定义兼容服务必须填写 Base URL");
        };
    }

    private void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean httpAllowed = properties.isAllowHttp() && "http".equalsIgnoreCase(uri.getScheme());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || httpAllowed)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw badRequest("INVALID_LLM_BASE_URL", "Base URL 必须是合法的 HTTPS 地址");
        }
    }

    private void ensureDefault() {
        Long current = mapper.defaultModelId();
        if (current != null && mapper.availableModel(current) != null) return;
        ModelOption first = mapper.firstAvailableModel();
        mapper.setDefaultModel(first == null ? null : first.id());
    }

    private ProviderRow requireProvider(long id) {
        ProviderRow provider = mapper.provider(id);
        if (provider == null) throw notFound("供应商配置不存在");
        return provider;
    }

    private ModelRow requireModel(long id) {
        ModelRow model = mapper.model(id);
        if (model == null) throw notFound("模型配置不存在");
        return model;
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private String safeMessage(Throwable exception, String secret) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (secret != null && !secret.isBlank()) message = message.replace(secret, "***");
        return message.substring(0, Math.min(1000, message.length()));
    }

    private LlmConfigurationException badRequest(String code, String message) {
        return new LlmConfigurationException(code, HttpStatus.BAD_REQUEST, message);
    }
    private LlmConfigurationException conflict(String code, String message) {
        return new LlmConfigurationException(code, HttpStatus.CONFLICT, message);
    }
    private LlmConfigurationException notFound(String message) {
        return new LlmConfigurationException("LLM_CONFIG_NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    public record ProviderCommand(String name, String providerType, String baseUrl, String apiKey,
                                  boolean enabled, List<ModelCommand> models) {}
    public record ModelCommand(String modelId, String displayName, boolean enabled) {}
    public record ConnectionTestCommand(Long providerId, Long modelConfigId, String name,
                                        String providerType, String baseUrl, String apiKey, String modelId) {}
    public record DiscoveryCommand(String providerType, String baseUrl, String apiKey) {}
    public record SavedDiscoveryCommand(String baseUrl, String apiKey) {}
    private record ValidatedProvider(String name, String normalizedName, String providerType,
                                     String protocolType, String baseUrl) {}
    private record ModelValues(String modelId, String normalizedModelId, String displayName) {}
}
