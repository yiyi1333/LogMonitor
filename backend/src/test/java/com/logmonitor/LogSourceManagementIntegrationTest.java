package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.service.LogSourceService;
import com.logmonitor.service.LogCollectorService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "log-monitor.admin.username=sourceadmin",
        "log-monitor.admin.password=SourceRoot123!",
        "log-monitor.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class LogSourceManagementIntegrationTest extends IntegrationTestSupport {
    private static final Path ROOT = createDirectory("log-source-root-");
    private static final Path SEEDED = createDirectory(ROOT, "seeded");
    private static final Path OUTSIDE = createDirectory("log-source-outside-");

    @Autowired MockMvc mvc;
    @Autowired LogMonitorMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired LogSourceService sources;
    @Autowired LogCollectorService collector;

    @DynamicPropertySource
    static void sourceProperties(DynamicPropertyRegistry registry) {
        registry.add("log-monitor.allowed-roots[0]", ROOT::toString);
        registry.add("log-monitor.sources[0].name", () -> "seeded-source");
        registry.add("log-monitor.sources[0].path", SEEDED::toString);
        registry.add("log-monitor.sources[0].uri-normalizers[0]", () -> "/\\d+");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (var paths = Files.walk(ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
        Files.deleteIfExists(OUTSIDE);
    }

    @Test
    void seedsManagesValidatesAndRestoresLogSourcesForBothRoles() throws Exception {
        MockHttpSession root = login("sourceadmin", "SourceRoot123!", false);
        mvc.perform(get("/api/sources/status").session(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceName").value("seeded-source"));
        assertThat(sources.activeSources().get(0).getUriNormalizers()).containsExactly("/\\d+");

        mapper.insertUser("sourceuser", encoder.encode("SourceUser123!"), "USER", false);
        MockHttpSession user = login("sourceuser", "SourceUser123!", false);
        mvc.perform(get("/api/sources/options").session(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedRoots[0]").value(ROOT.toRealPath().toString()));

        Path directory = Files.createDirectory(ROOT.resolve("dynamic"));
        Files.writeString(directory.resolve("application.log"), request("08:00:00.000", "/dynamic/one"));
        MvcResult created = mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Dynamic.Source", "path", directory.toString(),
                                "include", "*.log", "exclude", "*.error_*.log"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceName").value("Dynamic.Source"))
                .andReturn();
        long sourceId = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        await(() -> mapper.sourceCheckpointCount("Dynamic.Source") == 1);
        collector.scanNow(sourceId);
        assertThat(mapper.sourcePendingCheckpointCount("Dynamic.Source")).isZero();
        assertThat(mapper.totalAccessCount()).isEqualTo(1);

        Files.writeString(directory.resolve("application.log"), request("08:00:01.000", "/dynamic/one"),
                StandardOpenOption.APPEND);
        collector.scanNow(sourceId);
        collector.scanNow(sourceId);
        assertThat(mapper.totalAccessCount()).isEqualTo(2);

        Path another = Files.createDirectory(ROOT.resolve("another"));
        MvcResult duplicateName = mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "dynamic.source", "path", another.toString(),
                                "include", "*.log", "exclude", ""))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceName").value("dynamic.source"))
                .andReturn();
        long duplicateNameId = json.readTree(duplicateName.getResponse().getContentAsString()).get("id").asLong();
        assertThat(duplicateNameId).isNotEqualTo(sourceId);
        mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "other-source", "path", directory.toString(),
                                "include", "*.log", "exclude", ""))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_PATH_EXISTS"));
        mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad-pattern\",\"path\":\"" + another + "\",\"include\":\"[\",\"exclude\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_PATTERN"));
        mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad-path\",\"path\":\"relative/logs\",\"include\":\"*.log\",\"exclude\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_PATH_NOT_ALLOWED"));
        mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "parent-path",
                                "path", directory.resolve("..").resolve("dynamic").toString(),
                                "include", "*.log", "exclude", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_PATH_NOT_ALLOWED"));

        Path escapingLink = ROOT.resolve("escaping-link");
        Files.createSymbolicLink(escapingLink, OUTSIDE);
        mvc.perform(post("/api/sources").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "escaping-source", "path", escapingLink.toString(),
                                "include", "*.log", "exclude", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_PATH_NOT_ALLOWED"));

        mvc.perform(delete("/api/sources/{id}", sourceId).session(user).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(mapper.activeLogSource(sourceId)).isNull();
        assertThat(mapper.sourceCheckpointCount("Dynamic.Source")).isZero();
        assertThat(mapper.totalAccessCount()).isEqualTo(2);

        sources.run(new DefaultApplicationArguments(new String[0]));
        assertThat(mapper.activeLogSource(sourceId)).isNull();

        MvcResult restored = mvc.perform(post("/api/sources").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "dynamic.source", "path", directory.toString(),
                                "include", "*.log", "exclude", "*.error_*.log"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceName").value("Dynamic.Source"))
                .andReturn();
        assertThat(json.readTree(restored.getResponse().getContentAsString()).get("id").asLong()).isEqualTo(sourceId);
        await(() -> mapper.sourceCheckpointCount("Dynamic.Source") == 1);
        collector.scanNow(sourceId);
        assertThat(mapper.sourcePendingCheckpointCount("Dynamic.Source")).isZero();
        assertThat(mapper.totalAccessCount()).isEqualTo(2);
    }

    @Test
    void forcedPasswordChangeStillBlocksSourceManagement() throws Exception {
        mapper.insertUser("temporarysource", encoder.encode("Temporary123!"), "USER", true);
        MockHttpSession temporary = login("temporarysource", "Temporary123!", true);
        mvc.perform(get("/api/sources/options").session(temporary))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    private MockHttpSession login(String username, String password, boolean mustChange) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(mustChange))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void await(BooleanSupplier condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static Path createDirectory(String prefix) {
        try { return Files.createTempDirectory(prefix); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static Path createDirectory(Path parent, String child) {
        try { return Files.createDirectory(parent.resolve(child)); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String request(String time, String uri) {
        return "2026-08-17 " + time + "  INFO 100 --- [ http-nio-1] test.OncePerRequest : 当前请求URL:http://localhost"
                + uri + "，当前请求URI:" + uri + ",请求IP:127.0.0.1\n";
    }
}
