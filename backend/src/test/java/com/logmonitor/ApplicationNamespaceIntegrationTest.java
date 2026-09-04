package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.service.LogCollectorService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "log-monitor.admin.username=namespaceadmin",
        "log-monitor.admin.password=NamespaceRoot123!",
        "log-monitor.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class ApplicationNamespaceIntegrationTest extends IntegrationTestSupport {
    private static final Path ROOT = directory("namespace-root-");
    private static final Path FIRST = directory(ROOT, "orders-a");
    private static final Path SECOND = directory(ROOT, "orders-b");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LogCollectorService collector;
    @Autowired LogMonitorMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("log-monitor.allowed-roots[0]", ROOT::toString);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var paths = Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        }
    }

    @Test
    void batchesSameNamedSourcesAndAggregatesByNamespaceThenMigratesHistory() throws Exception {
        Files.writeString(FIRST.resolve("application.log"), request("10:00:00.000", "/orders/a"));
        Files.writeString(SECOND.resolve("application.log"), request("10:00:01.000", "/orders/b"));
        MockHttpSession session = login();

        Map<String, Object> first = source(FIRST);
        Map<String, Object> second = source(SECOND);
        String body = json.writeValueAsString(Map.of("collectorType", "LOCAL", "sources", List.of(first, second)));
        JsonNode created = json.readTree(mvc.perform(post("/api/sources/batch").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long firstId = created.get(0).get("id").asLong();
        long secondId = created.get(1).get("id").asLong();
        assertThat(firstId).isNotEqualTo(secondId);
        collector.scanNow(firstId);
        collector.scanNow(secondId);

        Instant from = Instant.parse("2026-08-20T01:00:00Z");
        Instant to = Instant.parse("2026-08-20T03:00:00Z");
        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "commerce"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(2));
        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "commerce").param("sourceId", String.valueOf(firstId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));
        JsonNode endpoints = json.readTree(mvc.perform(get("/api/endpoints").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "commerce"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items");
        assertThat(endpoints).hasSize(2);
        assertThat(java.util.stream.StreamSupport.stream(endpoints.spliterator(), false)
                .map(item -> item.get("service").asText()).toList()).containsOnly("orders");
        assertThat(java.util.stream.StreamSupport.stream(endpoints.spliterator(), false)
                .map(item -> item.get("applicationNamespace").asText()).toList()).containsOnly("commerce");
        long endpointId = endpoints.get(0).get("id").asLong();
        mvc.perform(get("/api/endpoints/{id}/trend", endpointId).session(session)
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].value").value(1));
        mvc.perform(get("/api/applications/options").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationNamespace").value("commerce"))
                .andExpect(jsonPath("$[0].instances.length()").value(2))
                .andExpect(jsonPath("$[0].instances[0].label").value(org.hamcrest.Matchers.containsString("local：orders（")));

        mvc.perform(patch("/api/sources/{id}/namespace", firstId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"applicationNamespace\":\"fulfillment\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"));
        await(() -> "IDLE".equals(mapper.activeLogSource(firstId).getNamespaceMigrationStatus()));

        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "fulfillment"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));
        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "commerce").param("sourceId", String.valueOf(firstId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NAMESPACE_MISMATCH"));

        mvc.perform(delete("/api/sources/{id}", firstId).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        JsonNode activeOptions = json.readTree(mvc.perform(get("/api/applications/options").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(java.util.stream.StreamSupport.stream(activeOptions.spliterator(), false)
                .map(item -> item.get("applicationNamespace").asText()).toList()).containsExactly("commerce");
        JsonNode activeServices = json.readTree(mvc.perform(get("/api/services").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(java.util.stream.StreamSupport.stream(activeServices.spliterator(), false)
                .map(JsonNode::asText).toList()).containsExactly("commerce");
        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "fulfillment"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));

        mvc.perform(delete("/api/sources/{id}", secondId).session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/applications/options").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/services").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/dashboard/summary").session(session)
                        .param("from", from.toString()).param("to", to.toString())
                        .param("applicationNamespace", "commerce"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));
    }

    private Map<String, Object> source(Path path) {
        return Map.of("name", "orders", "applicationNamespace", "commerce", "path", path.toString(),
                "include", "*.log", "exclude", "*.error_*.log", "startMode", "HISTORY_180D");
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"namespaceadmin\",\"password\":\"NamespaceRoot123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private void await(BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) Thread.sleep(30);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static String request(String time, String uri) {
        return "2026-08-20 " + time + "  INFO 100 --- [ http-nio-1] test.OncePerRequest : 当前请求URL:http://localhost"
                + uri + "，当前请求URI:" + uri + ",请求IP:127.0.0.1\n";
    }

    private static Path directory(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static Path directory(Path parent, String name) {
        try { return Files.createDirectory(parent.resolve(name)); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
