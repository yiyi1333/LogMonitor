package com.logmonitor;

import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class IntegrationTestSupport {
    @DynamicPropertySource
    static void sharedTestProperties(DynamicPropertyRegistry registry) {
        registry.add("log-monitor.llm.master-key", IntegrationTestSupport::masterKey);
    }

    public static String masterKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }
}
