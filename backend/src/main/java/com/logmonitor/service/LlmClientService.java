package com.logmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.logmonitor.config.LlmProperties;
import com.logmonitor.model.LlmModels.Completion;
import com.logmonitor.model.LlmModels.Connection;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;

@Service
public class LlmClientService {
    private final WebClient.Builder webClient;
    private final LlmProperties properties;

    public LlmClientService(WebClient.Builder webClient, LlmProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    public Completion complete(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        int attempts = test ? 0 : properties.getMaxRetries();
        RuntimeException last = null;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            try {
                return switch (connection.protocolType()) {
                    case "OPENAI_COMPATIBLE" -> openAi(connection, systemPrompt, userPrompt, test);
                    case "ANTHROPIC" -> anthropic(connection, systemPrompt, userPrompt, test);
                    case "GEMINI" -> gemini(connection, systemPrompt, userPrompt, test);
                    default -> throw new IllegalArgumentException("不支持的 LLM 协议: " + connection.protocolType());
                };
            } catch (RuntimeException exception) {
                last = exception;
                if (attempt == attempts || !retryable(exception)) break;
                try {
                    Thread.sleep(250L * (1L << attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("LLM 请求被中断", interrupted);
                }
            }
        }
        throw last == null ? new IllegalStateException("LLM 请求失败") : last;
    }

    private Completion openAi(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        LlmRequest request = openAiRequest(connection, systemPrompt, userPrompt, test);
        return parse("OPENAI_COMPATIBLE", execute(request, test));
    }

    LlmRequest openAiRequest(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.modelId());
        body.put("temperature", 0.2);
        body.put("max_tokens", test ? 128 : properties.getMaxOutputTokens());
        body.put("stream", false);
        if ("DEEPSEEK".equals(connection.providerType())) {
            body.put("thinking", Map.of("type", "disabled"));
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        return new LlmRequest(join(connection.baseUrl(), "/chat/completions"),
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey()), body);
    }

    private Completion anthropic(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        LlmRequest request = anthropicRequest(connection, systemPrompt, userPrompt, test);
        return parse("ANTHROPIC", execute(request, test));
    }

    LlmRequest anthropicRequest(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        Map<String, Object> body = Map.of(
                "model", connection.modelId(),
                "max_tokens", test ? 128 : properties.getMaxOutputTokens(),
                "temperature", 0.2,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));
        return new LlmRequest(join(connection.baseUrl(), "/v1/messages"),
                Map.of("x-api-key", connection.apiKey(), "anthropic-version", "2023-06-01"), body);
    }

    private Completion gemini(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        LlmRequest request = geminiRequest(connection, systemPrompt, userPrompt, test);
        return parse("GEMINI", execute(request, test));
    }

    LlmRequest geminiRequest(Connection connection, String systemPrompt, String userPrompt, boolean test) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of("temperature", 0.2,
                        "maxOutputTokens", test ? 128 : properties.getMaxOutputTokens(),
                        "responseMimeType", "application/json"));
        String model = UriUtils.encodePathSegment(connection.modelId(), java.nio.charset.StandardCharsets.UTF_8);
        return new LlmRequest(join(connection.baseUrl(), "/models/" + model + ":generateContent"),
                Map.of("x-goog-api-key", connection.apiKey()), body);
    }

    Completion parse(String protocol, JsonNode response) {
        return switch (protocol) {
            case "OPENAI_COMPATIBLE" -> completion(
                    response.path("choices").path(0).path("message").path("content").asText(null),
                    response.path("usage").path("prompt_tokens"), response.path("usage").path("completion_tokens"));
            case "ANTHROPIC" -> {
                String content = null;
                for (JsonNode item : response.path("content")) {
                    if ("text".equals(item.path("type").asText())) { content = item.path("text").asText(null); break; }
                }
                yield completion(content, response.path("usage").path("input_tokens"),
                        response.path("usage").path("output_tokens"));
            }
            case "GEMINI" -> completion(
                    response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null),
                    response.path("usageMetadata").path("promptTokenCount"),
                    response.path("usageMetadata").path("candidatesTokenCount"));
            default -> throw new IllegalArgumentException("不支持的 LLM 协议: " + protocol);
        };
    }

    private JsonNode execute(LlmRequest request, boolean test) {
        Duration responseTimeout = timeout(test);
        return webClient.build().post().uri(request.uri())
                .headers(headers -> request.headers().forEach(headers::set))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body())
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(responseTimeout)
                .onErrorMap(TimeoutException.class, exception -> timeoutFailure(test, responseTimeout, exception))
                .block();
    }

    private Completion completion(String content, JsonNode promptTokens, JsonNode completionTokens) {
        if (content == null || content.isBlank()) throw new IllegalStateException("LLM 未返回有效内容");
        return new Completion(content, number(promptTokens), number(completionTokens));
    }

    private Long number(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.longValue();
    }

    private Duration timeout(boolean test) {
        return Duration.ofSeconds(test ? properties.getTestTimeoutSeconds() : properties.getTimeoutSeconds());
    }

    private LlmRequestException timeoutFailure(boolean test, Duration timeout, Throwable cause) {
        String operation = test ? "连接测试" : "分析";
        return new LlmRequestException("LLM " + operation + "响应超时（" + timeout.toSeconds()
                + " 秒），请稍后重试并检查供应商状态或网络连接", cause);
    }

    private boolean retryable(RuntimeException exception) {
        if (exception instanceof LlmRequestException) return false;
        if (exception instanceof WebClientResponseException response) {
            return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
        }
        return exception instanceof WebClientRequestException
                || exception.getCause() instanceof java.util.concurrent.TimeoutException;
    }

    private URI join(String baseUrl, String path) {
        return URI.create(baseUrl.replaceAll("/+$", "") + path);
    }

    record LlmRequest(URI uri, Map<String, String> headers, Map<String, Object> body) {}

    static final class LlmRequestException extends RuntimeException {
        LlmRequestException(String message, Throwable cause) { super(message, cause); }
    }
}
