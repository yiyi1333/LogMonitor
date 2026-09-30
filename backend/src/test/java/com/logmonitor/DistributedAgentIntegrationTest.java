package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "log-monitor.admin.username=agentadmin",
        "log-monitor.admin.password=AgentRoot123!",
        "log-monitor.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class DistributedAgentIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LogMonitorMapper mapper;
    @Autowired PasswordEncoder encoder;

    @Test
    void clearsRecoveredErrorsWithoutClearingOtherOrOmittedSources() throws Exception {
        MockHttpSession root = login("agentadmin", "AgentRoot123!");
        Enrolled agent = enroll("recovery-agent", "host", "agentadmin", "AgentRoot123!");
        long first = createSource(root, agent.id);
        long second = createSource(root, agent.id, "/srv/logs/second");
        heartbeatReports(agent, List.of(errorReport(first, "first failure"), errorReport(second, "second failure")));
        assertAgent(root, agent, "ERROR", "first failure");

        heartbeatReports(agent, List.of(Map.of("sourceId", first, "status", "ACTIVE", "error", "stale message")));
        assertAgent(root, agent, "ERROR", "second failure");
        assertThat(jdbc.queryForObject("SELECT validation_error FROM log_source WHERE id=?", String.class, first)).isNull();
        heartbeatReports(agent, List.of());
        assertAgent(root, agent, "ERROR", "second failure");

        heartbeatReports(agent, List.of(Map.of("sourceId", second, "status", "ACTIVE")));
        assertAgent(root, agent, "ONLINE", null);
        assertThat(jdbc.queryForObject("SELECT last_error FROM collector_agent WHERE id=?", String.class, agent.id)).isNull();
        jdbc.update("UPDATE collector_agent SET spool_bytes=spool_limit_bytes WHERE id=?", agent.id);
        assertAgent(root, agent, "BLOCKED", null);
        jdbc.update("UPDATE collector_agent SET last_seen_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(120)), agent.id);
        assertAgent(root, agent, "OFFLINE", null);
    }

    @Test
    void ignoresDeletedUnknownAndForeignReportsFromLegacyAgents() throws Exception {
        MockHttpSession root = login("agentadmin", "AgentRoot123!");
        Enrolled agent = enroll("stale-agent", "host", "agentadmin", "AgentRoot123!");
        Enrolled other = enroll("foreign-agent", "host", "agentadmin", "AgentRoot123!");
        long deleted = createSource(root, agent.id);
        long foreign = createSource(root, other.id);
        heartbeatReports(agent, List.of(errorReport(deleted, "deleted failure")));
        mvc.perform(delete("/api/sources/{id}", deleted).session(root).with(csrf())).andExpect(status().isNoContent());
        heartbeatReports(agent, List.of(errorReport(deleted, "deleted failure"),
                errorReport(foreign, "foreign failure"), errorReport(Long.MAX_VALUE, "unknown failure")));
        assertAgent(root, agent, "ONLINE", null);
        assertThat(jdbc.queryForObject("SELECT validation_status FROM log_source WHERE id=?", String.class, foreign)).isEqualTo("VALIDATING");
        assertThat(jdbc.queryForObject("SELECT validation_error FROM log_source WHERE id=?", String.class, foreign)).isNull();
    }

    @Test
    void includesCenterDetectedPathConflictsAndClearsThemAfterRecovery() throws Exception {
        MockHttpSession root = login("agentadmin", "AgentRoot123!");
        Enrolled agent = enroll("conflict-agent", "host", "agentadmin", "AgentRoot123!");
        long first = createSource(root, agent.id);
        long second = createSource(root, agent.id, "/srv/logs/alias");
        heartbeat(agent, first, "ACTIVE", "/srv/logs/app");
        heartbeat(agent, second, "ACTIVE", "/srv/logs/app");
        assertAgent(root, agent, "ERROR", "远端真实目录已被当前 Agent 的其他来源使用");
        heartbeat(agent, second, "ACTIVE", "/srv/logs/unique");
        assertAgent(root, agent, "ONLINE", null);
    }

    @Test
    void enrollsValidatesIngestsDeduplicatesFiltersAndRevokesTwoAgents() throws Exception {
        MockHttpSession root = login("agentadmin", "AgentRoot123!");
        mapper.insertUser("agentoperator", encoder.encode("AgentUser123!"), "USER", false);
        MockHttpSession operator = login("agentoperator", "AgentUser123!");
        Enrolled first = enroll("prod-a", "host-a", "agentoperator", "AgentUser123!");
        Enrolled second = enroll("prod-b", "host-b", "agentadmin", "AgentRoot123!");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM collector_agent WHERE id=?", String.class, first.id))
                .isNotEqualTo(first.token).hasSize(64);

        mvc.perform(get("/api/agents").session(operator)).andExpect(status().isOk());
        long sourceA = createSource(operator, first.id);
        long sourceB = createSource(root, second.id);
        mvc.perform(get("/api/agent/v1/config").header("Authorization", bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].id").value(sourceA));
        heartbeat(first, sourceA, "ACTIVE", "/srv/logs/app");
        heartbeat(second, sourceB, "ACTIVE", "/srv/logs/app");

        byte[] event = request("/orders/42").getBytes(StandardCharsets.UTF_8);
        upload(first, sourceA, "batch-a-1", "file-a", "gen-a", 0, event, false, sha256(event), status().isOk());
        upload(first, sourceA, "batch-a-2", "file-a", "gen-a", event.length, new byte[0], true,
                sha256(new byte[0]), status().isOk());
        upload(first, sourceA, "batch-a-2", "file-a", "gen-a", event.length, new byte[0], true,
                sha256(new byte[0]), status().isOk());
        assertThat(mapper.totalAccessCount()).isEqualTo(1);

        upload(second, sourceB, "batch-b-1", "file-b", "gen-b", 0, event, false, sha256(event), status().isOk());
        upload(second, sourceB, "batch-b-2", "file-b", "gen-b", event.length, new byte[0], true,
                sha256(new byte[0]), status().isOk());
        assertThat(mapper.totalAccessCount()).isEqualTo(2);

        mvc.perform(get("/api/dashboard/summary").session(root).param("agentId", String.valueOf(first.id))
                        .param("from", "2026-08-18T00:00:00Z").param("to", "2026-08-18T02:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));
        mvc.perform(get("/api/dashboard/summary").session(root)
                        .param("from", "2026-08-18T00:00:00Z").param("to", "2026-08-18T02:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(2));

        upload(first, sourceA, "bad-checksum", "file-a", "gen-a", event.length, event, false,
                "0".repeat(64), status().isBadRequest());
        upload(first, sourceA, "bad-offset", "file-a", "gen-a", 1, new byte[0], false,
                sha256(new byte[0]), status().isConflict());

        mvc.perform(delete("/api/agents/{id}", first.id).session(operator).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/agent/v1/config").header("Authorization", bearer(first)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_REVOKED"));
        assertThat(mapper.totalAccessCount()).isEqualTo(2);
    }

    private Enrolled enroll(String name, String host, String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/v1/enroll").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "username", username, "password", password, "name", name,
                                "hostName", host, "version", "1.1.5",
                                "roots", List.of(Map.of("path", "/srv/logs", "realPath", "/srv/logs"))))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isString()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        return new Enrolled(body.get("agentId").asLong(), body.get("token").asText());
    }

    private long createSource(MockHttpSession session, long agentId) throws Exception {
        return createSource(session, agentId, "/srv/logs/app");
    }

    private long createSource(MockHttpSession session, long agentId, String path) throws Exception {
        MvcResult result = mvc.perform(post("/api/sources").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "name", "order-service", "collectorType", "AGENT", "agentId", agentId,
                                "path", path, "include", "*.log", "exclude", "*.error_*.log",
                                "startMode", "HISTORY_180D"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("VALIDATING")).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private Map<String, Object> errorReport(long sourceId, String error) {
        return Map.of("sourceId", sourceId, "status", "ERROR", "error", error);
    }

    private void heartbeatReports(Enrolled agent, List<Map<String, Object>> reports) throws Exception {
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "version", "1.0.0", "spoolBytes", 0, "spoolLimitBytes", 5368709120L, "sources", reports))))
                .andExpect(status().isNoContent());
    }

    private void assertAgent(MockHttpSession session, Enrolled agent, String state, String error) throws Exception {
        MvcResult result = mvc.perform(get("/api/agents").session(session)).andExpect(status().isOk()).andReturn();
        JsonNode agents = json.readTree(result.getResponse().getContentAsByteArray());
        JsonNode found = null;
        for (JsonNode item : agents) if (item.path("id").asLong() == agent.id) found = item;
        assertThat(found).isNotNull();
        assertThat(found.path("status").asText()).isEqualTo(state);
        assertThat(found.path("lastError").asText(null)).isEqualTo(error);
    }

    private void heartbeat(Enrolled agent, long sourceId, String state, String realPath) throws Exception {
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "version", "1.1.5", "spoolBytes", 0, "spoolLimitBytes", 5368709120L,
                                "sources", List.of(Map.of("sourceId", sourceId, "status", state,
                                        "realPath", realPath, "files", 1, "bytesRead", 0,
                                        "totalBytes", 0, "parseErrors", 0))))))
                .andExpect(status().isNoContent());
    }

    private void upload(Enrolled agent, long sourceId, String batchId, String fileKey, String generation,
                        long start, byte[] bytes, boolean stable, String checksum,
                        org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        long end = start + bytes.length;
        Map<String, Object> metadata = Map.ofEntries(
                Map.entry("batchId", batchId), Map.entry("sourceId", sourceId), Map.entry("fileKey", fileKey),
                Map.entry("generation", generation), Map.entry("path", "/srv/logs/app/application.log"),
                Map.entry("startOffset", start), Map.entry("endOffset", end), Map.entry("fileSize", end),
                Map.entry("modifiedAt", Instant.now().toString()), Map.entry("charset", "UTF-8"),
                Map.entry("checksum", checksum), Map.entry("stable", stable));
        MockMultipartFile metadataPart = new MockMultipartFile("metadata", "metadata.json",
                MediaType.APPLICATION_JSON_VALUE, json.writeValueAsBytes(metadata));
        MockMultipartFile payload = new MockMultipartFile("payload", "batch.gz", "application/gzip", gzip(bytes));
        mvc.perform(multipart("/api/agent/v1/batches").file(metadataPart).file(payload)
                        .header("Authorization", bearer(agent))).andExpect(expected);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(value); }
        return output.toByteArray();
    }
    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private String bearer(Enrolled agent) { return "Bearer " + agent.token; }
    private String request(String uri) {
        return "2026-08-18 09:00:00.000 INFO 100 --- [http-nio-1] test.OncePerRequest : 当前请求URL:http://localhost"
                + uri + "，当前请求URI:" + uri + ",请求IP:127.0.0.1\n";
    }
    private record Enrolled(long id, String token) {}
}
