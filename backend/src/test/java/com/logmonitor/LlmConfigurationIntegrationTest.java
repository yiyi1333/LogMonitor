package com.logmonitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.mapper.LlmMapper;
import com.logmonitor.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "log-monitor.admin.username=llmadmin",
        "log-monitor.admin.password=LlmRootPass123!",
        "log-monitor.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class LlmConfigurationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LlmMapper llmMapper;
    @Autowired AccountService accounts;

    @Test
    void enforcesRolesEncryptsSecretsAndFallsBackToTheDefaultModel() throws Exception {
        MockHttpSession root = login("llmadmin", "LlmRootPass123!");
        MvcResult created = mvc.perform(post("/api/admin/llm/providers").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"DeepSeek Test","providerType":"DEEPSEEK","baseUrl":"https://api.deepseek.com",
                                 "apiKey":"integration-secret","enabled":true,
                                 "models":[{"modelId":"deepseek-test","displayName":"DeepSeek Test","enabled":true}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKeyCiphertext").doesNotExist())
                .andExpect(jsonPath("$.models[0].modelId").value("deepseek-test"))
                .andReturn();
        JsonNode provider = json.readTree(created.getResponse().getContentAsString());
        long providerId = provider.path("id").asLong();
        long firstModelId = provider.path("models").path(0).path("id").asLong();

        String ciphertext = jdbc.queryForObject("SELECT api_key_ciphertext FROM llm_provider_config WHERE id=?",
                String.class, providerId);
        assertThat(ciphertext).doesNotContain("integration-secret");

        mvc.perform(get("/api/settings/llm").session(root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModelId").value(firstModelId))
                .andExpect(jsonPath("$.effectiveModel.id").value(firstModelId));

        accounts.createRegularUser("llm.user", "TempPass123");
        accounts.changePassword("llm.user", "TempPass123", "NewPass456");
        MockHttpSession user = login("llm.user", "NewPass456");
        mvc.perform(get("/api/admin/llm/providers").session(user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/llm/model-discovery").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerType\":\"ZHIPU\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/llm/preference").session(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":" + firstModelId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedModelId").value(firstModelId));

        mvc.perform(post("/api/admin/llm/model-discovery").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerType\":\"ZHIPU\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CATALOG"))
                .andExpect(jsonPath("$.models[0].modelId").value("glm-5.2"));

        MvcResult qwenCreated = mvc.perform(post("/api/admin/llm/providers").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Qwen Test","providerType":"QWEN","apiKey":"qwen-secret","enabled":true,
                                 "models":[{"modelId":"qwen-plus","displayName":"Qwen Plus","enabled":false}]}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long qwenProviderId = json.readTree(qwenCreated.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(post("/api/admin/llm/providers/{id}/model-discovery", qwenProviderId)
                        .session(root).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CATALOG"))
                .andExpect(jsonPath("$.models[3].modelId").value("qwen-plus"))
                .andExpect(jsonPath("$.models[3].alreadyConfigured").value(true));
        mvc.perform(post("/api/admin/llm/providers/{id}/models/import", qwenProviderId)
                        .session(root).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"models":[{"modelId":"qwen-plus","displayName":"Changed"},
                                           {"modelId":"qwen3.7-plus","displayName":"Qwen 3.7 Plus"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created[0].modelId").value("qwen3.7-plus"))
                .andExpect(jsonPath("$.skippedModelIds[0]").value("qwen-plus"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM llm_model_config WHERE provider_id=? " +
                "AND normalized_model_id='qwen-plus'", Boolean.class, qwenProviderId)).isFalse();
        mvc.perform(delete("/api/admin/llm/providers/{id}", qwenProviderId).session(root).with(csrf()))
                .andExpect(status().isNoContent());

        assertPreset(root, "KIMI", "Kimi Test", "kimi-test", "https://api.moonshot.ai/v1");
        assertPreset(root, "MINIMAX", "MiniMax Test", "minimax-test", "https://api.minimaxi.com/v1");
        assertPreset(root, "ZHIPU", "Zhipu Test", "glm-test", "https://open.bigmodel.cn/api/paas/v4");

        MvcResult second = mvc.perform(post("/api/admin/llm/providers/{id}/models", providerId).session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"deepseek-second\",\"displayName\":\"Second\",\"enabled\":true}"))
                .andExpect(status().isCreated()).andReturn();
        long secondModelId = json.readTree(second.getResponse().getContentAsString()).path("id").asLong();
        jdbc.update("INSERT INTO error_group(fingerprint,service_name,category,summary,first_seen,last_seen," +
                        "occurrence_count) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)",
                "a".repeat(64), "service", "SYSTEM", "summary");
        long groupId = jdbc.queryForObject("SELECT id FROM error_group WHERE fingerprint=?", Long.class,
                "a".repeat(64));
        jdbc.update("INSERT INTO ai_analysis(group_id,provider_config_id,model_config_id,provider_name," +
                        "provider_type,model_name,prompt_version,status) VALUES(?,?,?,?,?,?,?,'SUCCESS')",
                groupId, providerId, secondModelId, "DeepSeek Test", "DEEPSEEK", "deepseek-second", "concurrency-test");
        jdbc.update("INSERT INTO ai_analysis(group_id,provider_config_id,model_config_id,provider_name," +
                        "provider_type,model_name,prompt_version,locale,status) VALUES(?,?,?,?,?,?,?,?,'SUCCESS')",
                groupId, providerId, secondModelId, "DeepSeek Test", "DEEPSEEK", "deepseek-second",
                "concurrency-test", "en-US");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_analysis WHERE group_id=? AND prompt_version=?",
                Integer.class, groupId, "concurrency-test")).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_analysis(group_id,provider_config_id,model_config_id," +
                        "provider_name,provider_type,model_name,prompt_version,locale,status) " +
                        "VALUES(?,?,?,?,?,?,?,?,'SUCCESS')", groupId, providerId, secondModelId, "DeepSeek Test",
                "DEEPSEEK", "deepseek-second", "concurrency-test", "en-US"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        long analysisId = jdbc.queryForObject("SELECT id FROM ai_analysis " +
                "WHERE prompt_version='concurrency-test' AND locale='zh-CN'", Long.class);
        assertThat(llmMapper.restartAnalysis(analysisId, "llmadmin")).isEqualTo(1);
        assertThat(llmMapper.restartAnalysis(analysisId, "llmadmin")).isZero();

        mvc.perform(put("/api/admin/llm/default-model").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"modelId\":" + secondModelId + "}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/admin/llm/providers/{providerId}/models/{modelId}/status", providerId, firstModelId)
                        .session(root).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/settings/llm").session(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackApplied").value(true))
                .andExpect(jsonPath("$.effectiveModel.id").value(secondModelId));

        mvc.perform(delete("/api/admin/llm/providers/{id}", providerId).session(root).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM llm_provider_config", Integer.class)).isZero();
        mvc.perform(get("/api/settings/llm").session(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveModel").doesNotExist());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void assertPreset(MockHttpSession root, String type, String name, String modelId,
                              String expectedBaseUrl) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/llm/providers").session(root).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "name", name, "providerType", type, "apiKey", "preset-secret",
                                "enabled", true, "models", java.util.List.of(java.util.Map.of(
                                        "modelId", modelId, "displayName", modelId, "enabled", true))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseUrl").value(expectedBaseUrl))
                .andExpect(jsonPath("$.protocolType").value("OPENAI_COMPATIBLE"))
                .andReturn();
        long providerId = json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(delete("/api/admin/llm/providers/{id}", providerId).session(root).with(csrf()))
                .andExpect(status().isNoContent());
    }
}
