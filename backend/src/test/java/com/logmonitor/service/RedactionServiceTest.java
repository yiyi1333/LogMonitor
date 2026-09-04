package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class RedactionServiceTest {
    private final RedactionService service = new RedactionService();
    @Test void truncatesLongContexts() {
        String text = "line\n".repeat(120) + "tail";
        assertThat(service.truncateContext(text).lines().count()).isEqualTo(100);
    }
    @Test void redactsCredentialFields() {
        assertThat(service.redact("password=abc cookie:xyz accessKey=foo"))
                .isEqualTo("password=[REDACTED] cookie:[REDACTED] accessKey=[REDACTED]");
    }
}
