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

        mvc.perform(get("/api/dashboard/summary").session(root).param("agentId", String.valueOf(first.id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAccess").value(1));
        mvc.perform(get("/api/dashboard/summary").session(root))
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
                                "hostName", host, "version", "1.0.1",
                                "roots", List.of(Map.of("path", "/srv/logs", "realPath", "/srv/logs"))))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").isString()).andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsByteArray());
        return new Enrolled(body.get("agentId").asLong(), body.get("token").asText());
    }

    private long createSource(MockHttpSession session, long agentId) throws Exception {
        MvcResult result = mvc.perform(post("/api/sources").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "name", "order-service", "collectorType", "AGENT", "agentId", agentId,
                                "path", "/srv/logs/app", "include", "*.log", "exclude", "*.error_*.log",
                                "startMode", "HISTORY_180D"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("VALIDATING")).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private void heartbeat(Enrolled agent, long sourceId, String state, String realPath) throws Exception {
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization", bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "version", "1.0.1", "spoolBytes", 0, "spoolLimitBytes", 5368709120L,
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
