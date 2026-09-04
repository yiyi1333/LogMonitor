package com.logmonitor;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.UserAccount;
import com.logmonitor.security.UserRole;
import com.logmonitor.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
        "log-monitor.admin.username=rootadmin",
        "log-monitor.admin.password=RootPass123!",
        "log-monitor.sources[0].name=empty",
        "log-monitor.sources[0].path=./target/missing-security-test-logs"
})
@AutoConfigureMockMvc
class UserSecurityIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired LogMonitorMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired AccountService accounts;

    @Test
    void enforcesRootUserCreationForcedPasswordChangeAndOwnPasswordFlow() throws Exception {
        UserAccount root = mapper.userAccount("rootadmin");
        assertThat(root.role()).isEqualTo(UserRole.ROOT);
        assertThat(root.enabled()).isTrue();
        assertThat(root.mustChangePassword()).isFalse();

        accounts.ensureRootUser("rootadmin", "AnotherRootPassword");
        assertThat(encoder.matches("RootPass123!", mapper.userAccount("rootadmin").passwordHash())).isTrue();

        MockHttpSession rootSession = login("rootadmin", "RootPass123!", "ROOT", false);
        mvc.perform(post("/api/users").session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"New.User","initialPassword":"TempPass123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new.user"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        mvc.perform(post("/api/users").session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new.user","initialPassword":"TempPass123"}
                                """))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/users").session(rootSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].username").value("new.user"));

        MockHttpSession temporarySession = login("new.user", "TempPass123", "USER", true);
        mvc.perform(get("/api/dashboard/summary").session(temporarySession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        mvc.perform(post("/api/auth/password").session(temporarySession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"NewPass456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前密码错误"));

        mvc.perform(post("/api/auth/password").session(temporarySession).with(csrf())
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"NewPass456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"))
                .andExpect(jsonPath("$.message").value("The current password is incorrect"));

        mvc.perform(get("/api/dashboard/summary").header("Accept-Language", "ja-JP"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("セッションが期限切れです。再度ログインしてください"));

        mvc.perform(post("/api/auth/password").session(temporarySession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"TempPass123","newPassword":"NewPass456"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new.user","password":"TempPass123"}
                                """))
                .andExpect(status().isUnauthorized());

        MockHttpSession userSession = login("new.user", "NewPass456", "USER", false);
        mvc.perform(get("/api/dashboard/summary").session(userSession))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users").session(userSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rootCanRevokeSessionsDisableEnableAndDeleteRegularUsers() throws Exception {
        MockHttpSession rootSession = login("rootadmin", "RootPass123!", "ROOT", false);
        mvc.perform(post("/api/users").session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Lifecycle.User","initialPassword":"TempPass123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true));

        UserAccount created = mapper.userAccount("lifecycle.user");
        assertThat(created.sessionVersion()).isZero();
        MockHttpSession oldSession = login("lifecycle.user", "TempPass123", "USER", true);

        mvc.perform(patch("/api/users/{id}/status", created.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        long disabledVersion = mapper.userAccountById(created.id()).sessionVersion();

        mvc.perform(patch("/api/users/{id}/status", created.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(mapper.userAccountById(created.id()).sessionVersion()).isEqualTo(disabledVersion);

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lifecycle.user\",\"password\":\"TempPass123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mvc.perform(patch("/api/users/{id}/status", created.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(mapper.userAccountById(created.id()).sessionVersion()).isEqualTo(disabledVersion + 1);

        mvc.perform(get("/api/auth/me").session(oldSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));

        MockHttpSession activeSession = login("lifecycle.user", "TempPass123", "USER", true);
        MockHttpSession switchAccountSession = login("lifecycle.user", "TempPass123", "USER", true);
        mvc.perform(patch("/api/users/{id}/status", created.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(activeSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(post("/api/auth/login").session(switchAccountSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rootadmin\",\"password\":\"RootPass123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ROOT"));

        mvc.perform(patch("/api/users/{id}/status", created.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        MockHttpSession deleteSession = login("lifecycle.user", "TempPass123", "USER", true);

        UserAccount root = mapper.userAccount("rootadmin");
        mvc.perform(patch("/api/users/{id}/status", root.id()).session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(delete("/api/users/{id}", root.id()).session(rootSession).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mvc.perform(delete("/api/users/{id}", created.id()).session(rootSession).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(deleteSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(delete("/api/users/{id}", created.id()).session(rootSession).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/users").session(rootSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"lifecycle.user","initialPassword":"Replacement123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("lifecycle.user"));
    }

    private MockHttpSession login(String username, String password, String role, boolean mustChange) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.mustChangePassword").value(mustChange))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
