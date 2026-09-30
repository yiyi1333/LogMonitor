package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "log-monitor.admin.username=occurrenceadmin",
        "log-monitor.admin.password=OccurrenceRoot123!",
        "log-monitor.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class ErrorOccurrenceIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.logmonitor.mapper.LogMonitorMapper mapper;

    @Test
    void queriesStableStreamUpdatesDetailAndOccurrenceAnalysisSchema() throws Exception {
        MockHttpSession session = login();
        String service = "stream-only-" + java.util.UUID.randomUUID();
        mvc.perform(get("/api/errors/occurrences").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERROR_SERVICE_REQUIRED"));
        mvc.perform(get("/api/errors/occurrences").session(session)
                        .param("service", service).param("sort", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERROR_SORT_INVALID"));

        Instant occurredAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        long systemGroup = insertGroup(service, "SYSTEM", "Database unavailable", "SQLException");
        long businessGroup = insertGroup(service, "BUSINESS", "Session expired", "LoginException");
        long firstId = insertOccurrence(systemGroup, occurredAt, "worker-1", "connection refused", "stack-one", "event-first");
        long secondId = insertOccurrence(businessGroup, occurredAt, "worker-2", "user session expired", "stack-two", "event-second");

        mvc.perform(get("/api/services").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(service))));

        MvcResult descending = mvc.perform(get("/api/errors/occurrences").session(session)
                        .param("service", service)
                        .param("from", occurredAt.minusSeconds(60).toString())
                        .param("to", occurredAt.plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(secondId))
                .andExpect(jsonPath("$.items[1].id").value(firstId))
                .andExpect(jsonPath("$.items[0].messageText").doesNotExist())
                .andExpect(jsonPath("$.items[0].stackTrace").doesNotExist())
                .andReturn();
        long snapshotId = json.readTree(descending.getResponse().getContentAsString()).path("snapshotId").asLong();

        mvc.perform(get("/api/errors/occurrences").session(session)
                        .param("service", service)
                        .param("from", occurredAt.minusSeconds(60).toString())
                        .param("to", occurredAt.plusSeconds(60).toString())
                        .param("sort", "ASC").param("category", "SYSTEM").param("keyword", "connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(firstId));

        mvc.perform(get("/api/errors/occurrences/{id}", secondId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stackTrace").value("stack-two"))
                .andExpect(jsonPath("$.messageText").value("user session expired"))
                .andExpect(jsonPath("$.fingerprint").isNotEmpty());
        mvc.perform(get("/api/errors/occurrences/{id}", Long.MAX_VALUE).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERROR_OCCURRENCE_NOT_FOUND"));

        long thirdId = insertOccurrence(systemGroup, occurredAt.plusSeconds(1), "worker-3", "late event", "stack-three", "event-third");
        mvc.perform(get("/api/errors/occurrences/updates").session(session)
                        .param("service", service)
                        .param("from", occurredAt.minusSeconds(60).toString())
                        .param("to", occurredAt.plusSeconds(60).toString())
                        .param("afterId", String.valueOf(snapshotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.latestId").value(thirdId));

        long providerId = insertProvider();
        long modelId = insertModel(providerId);
        insertOccurrenceAnalysis(thirdId, providerId, modelId, "zh-CN");
        insertOccurrenceAnalysis(thirdId, providerId, modelId, "en-US");
        assertThatThrownBy(() -> insertOccurrenceAnalysis(thirdId, providerId, modelId, "en-US"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_occurrence_ai_analysis WHERE occurrence_id=?",
                Integer.class, thirdId)).isEqualTo(2);
        jdbc.update("DELETE FROM error_occurrence WHERE id=?", thirdId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_occurrence_ai_analysis WHERE occurrence_id=?",
                Integer.class, thirdId)).isZero();
    }

    @Test
    void groupFirstSeenIsHistoricalWhileCountsAndLastSeenRemainFiltered() throws Exception {
        String service = "historical-first-" + java.util.UUID.randomUUID();
        Instant historical = Instant.parse("2026-01-01T01:00:00Z");
        Instant first = Instant.parse("2026-09-29T01:00:00Z");
        Instant last = first.plusSeconds(60);
        long group = insertGroup(service, "SYSTEM", "Historical error", "SQLException");
        insertOccurrence(group, historical, "worker", "old", "stack", "historical");
        long firstId = insertOccurrence(group, first, "worker", "first", "stack", "first");
        long lastId = insertOccurrence(group, last, "worker", "last", "stack", "last");
        jdbc.update("UPDATE error_occurrence SET instance_key='instance-a' WHERE id=?", firstId);
        jdbc.update("UPDATE error_occurrence SET instance_key='instance-b' WHERE id=?", lastId);
        jdbc.update("UPDATE error_group SET first_seen=?,last_seen=?,occurrence_count=3 WHERE id=?",
                java.sql.Timestamp.from(historical), java.sql.Timestamp.from(last), group);

        Instant from = first.minusSeconds(1);
        Instant to = last.plusSeconds(1);
        MockHttpSession session = login();
        mvc.perform(get("/api/errors/groups").session(session).param("service", service)
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].firstSeen").value(historical.toString()))
                .andExpect(jsonPath("$.items[0].lastSeen").value(last.toString()))
                .andExpect(jsonPath("$.items[0].occurrenceCount").value(2));
        for (String instance : new String[]{"instance-a", "instance-b"}) {
            var rows = mapper.errorGroups(from, to, service, instance, null,
                    null, null, null, 20, 0);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).firstSeen()).isEqualTo(historical);
            assertThat(rows.get(0).occurrenceCount()).isEqualTo(1);
            assertThat(rows.get(0).lastSeen()).isEqualTo(instance.equals("instance-a") ? first : last);
        }
    }

    private long insertGroup(String service, String category, String summary, String exceptionClass) {
        String fingerprint = java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO error_group(fingerprint,service_name,category,exception_class,summary,first_seen," +
                        "last_seen,occurrence_count) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)",
                fingerprint, service, category, exceptionClass, summary);
        return jdbc.queryForObject("SELECT id FROM error_group WHERE fingerprint=?", Long.class, fingerprint);
    }

    private long insertOccurrence(long groupId, Instant occurredAt, String thread, String message,
                                  String stack, String eventSuffix) {
        String eventKey = java.util.UUID.randomUUID() + "-" + eventSuffix;
        jdbc.update("INSERT INTO error_occurrence(group_id,occurred_at,thread_name,message_text,stack_trace," +
                        "association_type,source_path,source_offset,event_key,instance_key) VALUES(?,?,?,?,?,'NONE',?,?,?,'local')",
                groupId, occurredAt, thread, message, stack, "/var/log/app.log", 128L, eventKey);
        return jdbc.queryForObject("SELECT id FROM error_occurrence WHERE event_key=?", Long.class, eventKey);
    }

    private long insertProvider() {
        String normalized = "provider-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO llm_provider_config(name,normalized_name,provider_type,protocol_type,base_url," +
                        "api_key_ciphertext,api_key_nonce,created_by) VALUES(?,?,'DEEPSEEK','OPENAI_COMPATIBLE'," +
                        "'https://example.invalid','cipher','nonce','occurrenceadmin')", normalized, normalized);
        return jdbc.queryForObject("SELECT id FROM llm_provider_config WHERE normalized_name=?", Long.class, normalized);
    }

    private long insertModel(long providerId) {
        String model = "model-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO llm_model_config(provider_id,model_id,normalized_model_id,display_name) VALUES(?,?,?,?)",
                providerId, model, model, model);
        return jdbc.queryForObject("SELECT id FROM llm_model_config WHERE provider_id=? AND normalized_model_id=?",
                Long.class, providerId, model);
    }

    private void insertOccurrenceAnalysis(long occurrenceId, long providerId, long modelId, String locale) {
        jdbc.update("INSERT INTO error_occurrence_ai_analysis(occurrence_id,provider_config_id,model_config_id," +
                        "provider_name,provider_type,model_name,prompt_version,locale,status) " +
                        "VALUES(?,?,?,'Test','DEEPSEEK','test-model','v4-occurrence-localized',?,'SUCCESS')",
                occurrenceId, providerId, modelId, locale);
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "occurrenceadmin", "password", "OccurrenceRoot123!"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
