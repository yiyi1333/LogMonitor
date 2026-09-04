package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.config.LlmProperties;
import com.logmonitor.model.LlmModels.Completion;
import com.logmonitor.model.LlmModels.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class LlmClientServiceDeepSeekSmokeTest {
    @Test
    void completesStructuredAnalysisWithRealDeepSeekWhenKeyIsProvided() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_SMOKE_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "Set DEEPSEEK_SMOKE_API_KEY to run the real provider smoke test");

        LlmProperties properties = new LlmProperties();
        properties.setTimeoutSeconds(120);
        properties.setMaxRetries(0);
        properties.setMaxOutputTokens(512);
        LlmClientService client = new LlmClientService(WebClient.builder(), properties);
        Connection connection = new Connection(0, 0, "DeepSeek", "DEEPSEEK", "OPENAI_COMPATIBLE",
                "https://api.deepseek.com", apiKey, "deepseek-v4-flash");

        Completion completion = client.complete(connection,
                "Only output JSON with an overview string and no Markdown.",
                "Analyze this sanitized error: java.lang.IllegalStateException: test failure", false);

        assertThat(new ObjectMapper().readTree(completion.content()).path("overview").asText()).isNotBlank();
    }
}
