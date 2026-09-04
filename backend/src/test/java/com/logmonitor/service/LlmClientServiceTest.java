package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.config.LlmProperties;
import com.logmonitor.model.LlmModels.Completion;
import com.logmonitor.model.LlmModels.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class LlmClientServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LlmClientService client = client();

    @Test
    void buildsAndParsesOpenAiCompatibleRequests() throws Exception {
        LlmClientService.LlmRequest request = client.openAiRequest(
                connection("OPENAI_COMPATIBLE", "deepseek-v4-flash"), "system", "log", false);
        Completion completion = client.parse("OPENAI_COMPATIBLE", node("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":8}}
                """));

        assertThat(request.uri().toString()).isEqualTo("https://llm.example/chat/completions");
        assertThat(request.headers()).containsEntry("Authorization", "Bearer test-key");
        assertThat(request.body().toString()).contains("deepseek-v4-flash").contains("system").contains("log");
        assertThat(completion.promptTokens()).isEqualTo(12);
        assertThat(completion.completionTokens()).isEqualTo(8);
    }

    @Test
    void configuresDeepSeekForFastStructuredJsonOutput() {
        LlmClientService.LlmRequest request = client.openAiRequest(
                new Connection(1, 2, "DeepSeek", "DEEPSEEK", "OPENAI_COMPATIBLE",
                        "https://api.deepseek.com", "test-key", "deepseek-v4-flash"),
                "Return JSON.", "Analyze the error.", false);

        assertThat(request.body()).containsEntry("stream", false)
                .containsEntry("thinking", Map.of("type", "disabled"))
                .containsEntry("response_format", Map.of("type", "json_object"));
    }

    @Test
    void buildsAndParsesAnthropicRequests() throws Exception {
        LlmClientService.LlmRequest request = client.anthropicRequest(
                connection("ANTHROPIC", "claude-test"), "system", "log", false);
        Completion completion = client.parse("ANTHROPIC", node("""
                {"content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":10,"output_tokens":7}}
                """));

        assertThat(request.uri().toString()).isEqualTo("https://llm.example/v1/messages");
        assertThat(request.headers()).containsEntry("x-api-key", "test-key")
                .containsEntry("anthropic-version", "2023-06-01");
        assertThat(request.body()).containsEntry("model", "claude-test");
        assertThat(completion.promptTokens()).isEqualTo(10);
    }

    @Test
    void buildsAndParsesGeminiRequests() throws Exception {
        LlmClientService.LlmRequest request = client.geminiRequest(
                connection("GEMINI", "gemini-test"), "system", "log", false);
        Completion completion = client.parse("GEMINI", node("""
                {"candidates":[{"content":{"parts":[{"text":"ok"}]}}],
                 "usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":6}}
                """));

        assertThat(request.uri().toString()).isEqualTo("https://llm.example/models/gemini-test:generateContent");
        assertThat(request.headers()).containsEntry("x-goog-api-key", "test-key");
        assertThat(request.body()).containsKeys("systemInstruction", "generationConfig");
        assertThat(completion.completionTokens()).isEqualTo(6);
    }

    @Test
    void retriesRateLimitsAndServerErrorsThenReturnsTheResponse() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) return Mono.just(response(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"slow down\"}"));
            if (attempt == 2) return Mono.just(response(HttpStatus.BAD_GATEWAY, "{\"error\":\"upstream\"}"));
            return Mono.just(response(HttpStatus.OK,
                    "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        };
        LlmProperties properties = new LlmProperties();
        properties.setMaxRetries(2);
        LlmClientService retrying = new LlmClientService(WebClient.builder().exchangeFunction(exchange), properties);

        assertThat(retrying.complete(connection("OPENAI_COMPATIBLE", "model"), "system", "log", false).content())
                .isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void doesNotRetryNonRateLimitedClientErrors() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            attempts.incrementAndGet();
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer test-key");
            return Mono.just(response(HttpStatus.UNAUTHORIZED, "{\"error\":\"unauthorized\"}"));
        };
        LlmProperties properties = new LlmProperties();
        properties.setMaxRetries(2);
        LlmClientService retrying = new LlmClientService(WebClient.builder().exchangeFunction(exchange), properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        retrying.complete(connection("OPENAI_COMPATIBLE", "model"), "system", "log", false))
                .isInstanceOf(WebClientResponseException.Unauthorized.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void reportsReadableTimeoutWithoutRetryingTheRequest() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            attempts.incrementAndGet();
            return Mono.never();
        };
        LlmProperties properties = new LlmProperties();
        properties.setTimeoutSeconds(1);
        properties.setMaxRetries(2);
        LlmClientService timingOut = new LlmClientService(
                WebClient.builder().exchangeFunction(exchange), properties);

        assertThatThrownBy(() -> timingOut.complete(
                connection("OPENAI_COMPATIBLE", "model"), "system", "log", false))
                .isInstanceOf(LlmClientService.LlmRequestException.class)
                .hasMessage("LLM 分析响应超时（1 秒），请稍后重试并检查供应商状态或网络连接")
                .hasMessageNotContaining("NANOSECONDS");
        assertThat(attempts).hasValue(1);
    }

    private ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }

    private LlmClientService client() {
        LlmProperties properties = new LlmProperties();
        properties.setTimeoutSeconds(3);
        properties.setMaxRetries(0);
        return new LlmClientService(WebClient.builder(), properties);
    }

    private Connection connection(String protocol, String model) {
        return new Connection(1, 2, "provider", protocol, protocol,
                "https://llm.example", "test-key", model);
    }

    private JsonNode node(String value) throws Exception { return json.readTree(value); }
}
