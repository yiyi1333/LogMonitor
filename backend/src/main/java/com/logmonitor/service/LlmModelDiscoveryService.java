package com.logmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.logmonitor.config.LlmProperties;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class LlmModelDiscoveryService {
    static final String CATALOG_VERSION = "2026-08-18";
    private static final int MAX_MODELS = 1000;
    private static final Map<String, List<CatalogModel>> CATALOGS = catalogs();

    private final WebClient webClient;
    private final LlmProperties properties;

    public LlmModelDiscoveryService(WebClient.Builder webClient, LlmProperties properties) {
        this.webClient = webClient.clone()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        this.properties = properties;
    }

    public DiscoveryResult discover(DiscoveryConnection connection) {
        List<CatalogModel> catalog = CATALOGS.get(connection.providerType());
        if (catalog != null) {
            return new DiscoveryResult("CATALOG", CATALOG_VERSION,
                    "结果来自内置官方目录，不代表当前 API Key 已开通调用权限。",
                    catalog.stream().map(model -> new Candidate(model.modelId(), model.displayName(),
                            null, "SUPPORTED")).toList());
        }
        try {
            List<Candidate> candidates = switch (connection.protocolType()) {
                case "OPENAI_COMPATIBLE" -> openAi(connection);
                case "ANTHROPIC" -> anthropic(connection);
                case "GEMINI" -> gemini(connection);
                default -> throw unsupported("当前供应商协议不支持模型发现");
            };
            return new DiscoveryResult("REMOTE", null, null, normalize(candidates));
        } catch (LlmConfigurationException exception) {
            throw exception;
        } catch (WebClientResponseException exception) {
            if ("OPENAI_COMPATIBLE".equals(connection.providerType())
                    && (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 405)) {
                throw unsupported("该 OpenAI 兼容服务未提供 /models 接口，请手工录入模型 ID");
            }
            throw failed("模型列表请求失败，供应商返回 HTTP " + exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            if (exception instanceof InvalidModelListException || exception instanceof DecodingException
                    || exception.getCause() instanceof DecodingException) {
                throw invalid("供应商返回了无法识别的模型列表");
            }
            throw failed("模型列表请求失败，请检查 Base URL、网络和 API Key");
        }
    }

    private List<Candidate> openAi(DiscoveryConnection connection) {
        JsonNode response = getWithRetry(join(connection.baseUrl(), "/models"),
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey()));
        JsonNode data = requiredArray(response, "data");
        List<Candidate> result = new ArrayList<>();
        for (JsonNode item : data) {
            String id = text(item, "id");
            if (validId(id)) {
                result.add(new Candidate(id, id, nullableText(item, "owned_by"), "UNKNOWN"));
            }
        }
        return result;
    }

    private List<Candidate> anthropic(DiscoveryConnection connection) {
        List<Candidate> result = new ArrayList<>();
        String afterId = null;
        while (result.size() < MAX_MODELS) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUri(join(connection.baseUrl(), "/v1/models"))
                    .queryParam("limit", MAX_MODELS);
            if (afterId != null) uri.queryParam("after_id", afterId);
            JsonNode response = getWithRetry(uri.build(true).toUri(), Map.of(
                    "x-api-key", connection.apiKey(), "anthropic-version", "2023-06-01"));
            JsonNode data = requiredArray(response, "data");
            for (JsonNode item : data) {
                String id = text(item, "id");
                if (validId(id)) {
                    String name = nullableText(item, "display_name");
                    result.add(new Candidate(id, name == null ? id : name, "Anthropic", "SUPPORTED"));
                }
                if (result.size() >= MAX_MODELS) break;
            }
            if (!response.path("has_more").asBoolean(false) || data.isEmpty()) break;
            String next = nullableText(response, "last_id");
            if (next == null || next.equals(afterId)) throw new InvalidModelListException();
            afterId = next;
        }
        return result;
    }

    private List<Candidate> gemini(DiscoveryConnection connection) {
        List<Candidate> result = new ArrayList<>();
        String pageToken = null;
        while (result.size() < MAX_MODELS) {
            UriComponentsBuilder uri = UriComponentsBuilder.fromUri(join(connection.baseUrl(), "/models"))
                    .queryParam("pageSize", MAX_MODELS);
            if (pageToken != null) uri.queryParam("pageToken", pageToken);
            JsonNode response = getWithRetry(uri.build(true).toUri(),
                    Map.of("x-goog-api-key", connection.apiKey()));
            JsonNode models = requiredArray(response, "models");
            for (JsonNode item : models) {
                if (!contains(item.path("supportedGenerationMethods"), "generateContent")) continue;
                String name = text(item, "name");
                String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
                if (validId(id)) {
                    String displayName = nullableText(item, "displayName");
                    result.add(new Candidate(id, displayName == null ? id : displayName,
                            "Google", "SUPPORTED"));
                }
                if (result.size() >= MAX_MODELS) break;
            }
            String next = nullableText(response, "nextPageToken");
            if (next == null || next.isBlank()) break;
            if (next.equals(pageToken)) throw new InvalidModelListException();
            pageToken = next;
        }
        return result;
    }

    private JsonNode getWithRetry(URI uri, Map<String, String> headers) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode response = webClient.get().uri(uri)
                        .headers(values -> headers.forEach(values::set))
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve().bodyToMono(JsonNode.class)
                        .block(Duration.ofSeconds(properties.getTestTimeoutSeconds()));
                if (response == null || response.isNull()) throw new InvalidModelListException();
                return response;
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == 1 || !retryable(exception)) break;
            }
        }
        throw last == null ? new IllegalStateException("模型列表请求失败") : last;
    }

    private List<Candidate> normalize(List<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (!validId(candidate.modelId())) continue;
            unique.putIfAbsent(candidate.modelId().toLowerCase(Locale.ROOT), candidate);
            if (unique.size() >= MAX_MODELS) break;
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(Candidate::modelId, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private boolean retryable(RuntimeException exception) {
        if (exception instanceof WebClientResponseException response) {
            return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
        }
        return exception instanceof WebClientRequestException
                || exception.getCause() instanceof java.util.concurrent.TimeoutException;
    }

    private JsonNode requiredArray(JsonNode response, String field) {
        JsonNode value = response.path(field);
        if (!value.isArray()) throw new InvalidModelListException();
        return value;
    }

    private String text(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) throw new InvalidModelListException();
        return value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }

    private boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 160;
    }

    private URI join(String baseUrl, String path) {
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    private LlmConfigurationException unsupported(String message) {
        return new LlmConfigurationException("LLM_MODEL_DISCOVERY_UNSUPPORTED", HttpStatus.BAD_REQUEST, message);
    }

    private LlmConfigurationException failed(String message) {
        return new LlmConfigurationException("LLM_MODEL_DISCOVERY_FAILED", HttpStatus.BAD_GATEWAY, message);
    }

    private LlmConfigurationException invalid(String message) {
        return new LlmConfigurationException("LLM_MODEL_LIST_INVALID", HttpStatus.BAD_GATEWAY, message);
    }

    private static Map<String, List<CatalogModel>> catalogs() {
        return Map.of(
                "QWEN", List.of(
                        model("qwen3.7-plus", "Qwen 3.7 Plus"),
                        model("qwen3.6-plus", "Qwen 3.6 Plus"),
                        model("qwen3.5-plus", "Qwen 3.5 Plus"),
                        model("qwen-plus", "Qwen Plus"),
                        model("qwen3.8-max", "Qwen 3.8 Max"),
                        model("qwen3.7-max", "Qwen 3.7 Max"),
                        model("qwen3.6-max-preview", "Qwen 3.6 Max Preview"),
                        model("qwen3-max", "Qwen 3 Max"),
                        model("qwen-max", "Qwen Max"),
                        model("qwen3.7-flash", "Qwen 3.7 Flash"),
                        model("qwen3.6-flash", "Qwen 3.6 Flash"),
                        model("qwen3.5-flash", "Qwen 3.5 Flash"),
                        model("qwen-flash", "Qwen Flash"),
                        model("qwen-turbo", "Qwen Turbo"),
                        model("qwen3-coder-plus", "Qwen 3 Coder Plus"),
                        model("qwen3-coder-next", "Qwen 3 Coder Next"),
                        model("qwen3-coder-flash", "Qwen 3 Coder Flash")),
                "ZHIPU", List.of(
                        model("glm-5.2", "GLM-5.2"),
                        model("glm-5.1", "GLM-5.1"),
                        model("glm-5-turbo", "GLM-5 Turbo"),
                        model("glm-5", "GLM-5"),
                        model("glm-4.7", "GLM-4.7"),
                        model("glm-4.7-flash", "GLM-4.7 Flash"),
                        model("glm-4.7-flashx", "GLM-4.7 FlashX"),
                        model("glm-4.6", "GLM-4.6"),
                        model("glm-4.5-air", "GLM-4.5 Air"),
                        model("glm-4.5-airx", "GLM-4.5 AirX"),
                        model("glm-4.5-flash", "GLM-4.5 Flash")));
    }

    private static CatalogModel model(String id, String name) { return new CatalogModel(id, name); }

    public record DiscoveryConnection(String providerType, String protocolType, String baseUrl, String apiKey) {}
    public record Candidate(String modelId, String displayName, String owner, String capabilityStatus) {}
    public record DiscoveryResult(String source, String catalogVersion, String warning, List<Candidate> models) {}
    private record CatalogModel(String modelId, String displayName) {}
    private static final class InvalidModelListException extends RuntimeException {}
}
