package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logmonitor.config.LlmProperties;
import com.logmonitor.service.LlmModelDiscoveryService.DiscoveryConnection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class LlmModelDiscoveryServiceTest {
    @Test
    void parsesOpenAiListsAndRetriesTransientFailures() {
        AtomicInteger attempts = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            assertThat(request.url().toString()).isEqualTo("https://api.example/v1/models");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer secret-key");
            if (attempts.incrementAndGet() == 1) {
                return Mono.just(response(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"slow\"}"));
            }
            return Mono.just(response(HttpStatus.OK, """
                    {"data":[
                      {"id":"chat-model","owned_by":"vendor"},
                      {"id":"CHAT-MODEL","owned_by":"vendor"},
                      {"id":"embedding-model","owned_by":"vendor"}
                    ]}
                    """));
        };

        var result = service(exchange).discover(connection("KIMI", "OPENAI_COMPATIBLE"));

        assertThat(attempts).hasValue(2);
        assertThat(result.source()).isEqualTo("REMOTE");
        assertThat(result.models()).extracting(LlmModelDiscoveryService.Candidate::modelId)
                .containsExactly("chat-model", "embedding-model");
        assertThat(result.models()).allMatch(model -> "UNKNOWN".equals(model.capabilityStatus()));
    }

    @Test
    void followsAnthropicCursorPagination() {
        AtomicInteger requests = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            assertThat(request.headers().getFirst("x-api-key")).isEqualTo("secret-key");
            assertThat(request.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
            if (requests.incrementAndGet() == 1) {
                assertThat(request.url().getQuery()).doesNotContain("after_id");
                return Mono.just(response(HttpStatus.OK, """
                        {"data":[{"id":"claude-new","display_name":"Claude New"}],
                         "has_more":true,"last_id":"cursor-1"}
                        """));
            }
            assertThat(request.url().getQuery()).contains("after_id=cursor-1");
            return Mono.just(response(HttpStatus.OK, """
                    {"data":[{"id":"claude-old","display_name":"Claude Old"}],"has_more":false}
                    """));
        };

        var result = service(exchange).discover(connection("ANTHROPIC", "ANTHROPIC"));

        assertThat(requests).hasValue(2);
        assertThat(result.models()).extracting(LlmModelDiscoveryService.Candidate::displayName)
                .containsExactly("Claude New", "Claude Old");
    }

    @Test
    void filtersGeminiModelsThatCannotGenerateContentAndFollowsPageTokens() {
        AtomicInteger requests = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            assertThat(request.headers().getFirst("x-goog-api-key")).isEqualTo("secret-key");
            if (requests.incrementAndGet() == 1) {
                return Mono.just(response(HttpStatus.OK, """
                        {"models":[
                          {"name":"models/gemini-chat","displayName":"Gemini Chat",
                           "supportedGenerationMethods":["generateContent"]},
                          {"name":"models/gemini-embed","supportedGenerationMethods":["embedContent"]}
                        ],"nextPageToken":"next-page"}
                        """));
            }
            assertThat(request.url().getQuery()).contains("pageToken=next-page");
            return Mono.just(response(HttpStatus.OK, """
                    {"models":[{"name":"models/gemini-fast","displayName":"Gemini Fast",
                     "supportedGenerationMethods":["generateContent"]}]}
                    """));
        };

        var result = service(exchange).discover(connection("GEMINI", "GEMINI"));

        assertThat(result.models()).extracting(LlmModelDiscoveryService.Candidate::modelId)
                .containsExactly("gemini-chat", "gemini-fast");
    }

    @Test
    void returnsVersionedCatalogsWithoutCallingTheNetwork() {
        ExchangeFunction exchange = request -> {
            throw new AssertionError("目录供应商不应访问网络");
        };

        var qwen = service(exchange).discover(connection("QWEN", "OPENAI_COMPATIBLE"));
        var zhipu = service(exchange).discover(connection("ZHIPU", "OPENAI_COMPATIBLE"));

        assertThat(qwen.source()).isEqualTo("CATALOG");
        assertThat(qwen.catalogVersion()).isEqualTo("2026-08-18");
        assertThat(qwen.models()).extracting(LlmModelDiscoveryService.Candidate::modelId)
                .contains("qwen3.7-plus");
        assertThat(zhipu.models()).extracting(LlmModelDiscoveryService.Candidate::modelId)
                .contains("glm-5.2");
    }

    @Test
    void reportsUnsupportedCustomListsAndInvalidResponsesWithoutLeakingKeys() {
        var unsupported = service(request -> Mono.just(response(HttpStatus.NOT_FOUND, "not found")));
        assertThatThrownBy(() -> unsupported.discover(connection("OPENAI_COMPATIBLE", "OPENAI_COMPATIBLE")))
                .isInstanceOfSatisfying(LlmConfigurationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("LLM_MODEL_DISCOVERY_UNSUPPORTED");
                    assertThat(exception.getMessage()).doesNotContain("secret-key");
                });

        var invalid = service(request -> Mono.just(response(HttpStatus.OK, "{\"unexpected\":[]}")));
        assertThatThrownBy(() -> invalid.discover(connection("MINIMAX", "OPENAI_COMPATIBLE")))
                .isInstanceOfSatisfying(LlmConfigurationException.class, exception ->
                        assertThat(exception.code()).isEqualTo("LLM_MODEL_LIST_INVALID"));

        var malformed = service(request -> Mono.just(response(HttpStatus.OK, "{not-json")));
        assertThatThrownBy(() -> malformed.discover(connection("KIMI", "OPENAI_COMPATIBLE")))
                .isInstanceOfSatisfying(LlmConfigurationException.class, exception ->
                        assertThat(exception.code()).isEqualTo("LLM_MODEL_LIST_INVALID"));
    }

    private LlmModelDiscoveryService service(ExchangeFunction exchange) {
        LlmProperties properties = new LlmProperties();
        properties.setTestTimeoutSeconds(2);
        return new LlmModelDiscoveryService(WebClient.builder().exchangeFunction(exchange), properties);
    }

    private DiscoveryConnection connection(String providerType, String protocol) {
        return new DiscoveryConnection(providerType, protocol, "https://api.example/v1", "secret-key");
    }

    private ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build();
    }
}
