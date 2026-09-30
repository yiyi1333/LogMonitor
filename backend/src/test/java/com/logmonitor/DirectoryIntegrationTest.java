package com.logmonitor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.nio.file.*;
import java.util.Comparator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"log-monitor.admin.username=directoryadmin","log-monitor.admin.password=DirectoryRoot123!","log-monitor.initial-delay-ms=600000"})
@AutoConfigureMockMvc
class DirectoryIntegrationTest extends IntegrationTestSupport {
    private static final Path ROOT=createRoot();
    private static Path createRoot(){try{Path root=Files.createTempDirectory("directory-http-").toRealPath();Files.createDirectory(root.resolve("app"));return root;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void roots(DynamicPropertyRegistry registry){registry.add("log-monitor.allowed-roots",()->ROOT.toString());}
    @Autowired MockMvc mvc;
    @Test void requiresSessionAndPreservesAuthenticationAcrossAsyncDirectoryResponse() throws Exception {
        mvc.perform(get("/api/sources/directories")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"directoryadmin\",\"password\":\"DirectoryRoot123!\"}")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        var request=mvc.perform(get("/api/sources/directories").session(session).param("path",ROOT.toString())).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andExpect(jsonPath("$.directories[0].name").value("app"));
        var forbidden=mvc.perform(get("/api/sources/directories").session(session).param("path",ROOT.getParent().toString())).andReturn();
        mvc.perform(asyncDispatch(forbidden)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DIRECTORY_FORBIDDEN"));
        mvc.perform(get("/api/agent/v1/directories/requests").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedAgentReceivesAsyncCommandAndCompletesOnlyItsResult() throws Exception {
        var login=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"username\":\"directoryadmin\",\"password\":\"DirectoryRoot123!\"}")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var enrolled=mvc.perform(post("/api/agent/v1/enroll").contentType("application/json").content(json.writeValueAsBytes(java.util.Map.of("username","directoryadmin","password","DirectoryRoot123!","name","dir-"+java.util.UUID.randomUUID(),"hostName","fixture","version","1.1.2","roots",java.util.List.of(java.util.Map.of("path",ROOT.toString(),"realPath",ROOT.toString())))))).andExpect(status().isCreated()).andReturn();
        var enrollment=json.readTree(enrolled.getResponse().getContentAsString());String bearer="Bearer "+enrollment.path("token").asText();long agentId=enrollment.path("agentId").asLong();
        mvc.perform(post("/api/agent/v1/heartbeat").header("Authorization",bearer).contentType("application/json").content("{\"version\":\"1.1.2\",\"sources\":[],\"spoolBytes\":0,\"spoolLimitBytes\":100}")).andExpect(status().isNoContent());
        var poll=mvc.perform(get("/api/agent/v1/directories/requests").header("Authorization",bearer)).andExpect(request().asyncStarted()).andReturn();
        var browse=mvc.perform(get("/api/sources/directories").session(session).param("agentId",Long.toString(agentId)).param("path",ROOT.toString())).andExpect(request().asyncStarted()).andReturn();
        var delivered=mvc.perform(asyncDispatch(poll)).andExpect(status().isOk()).andReturn();
        String id=json.readTree(delivered.getResponse().getContentAsString()).path("requestId").asText();
        mvc.perform(post("/api/agent/v1/directories/results").header("Authorization",bearer).contentType("application/json").content(json.writeValueAsBytes(java.util.Map.of("requestId",id,"listing",java.util.Map.of("path",ROOT.toString(),"directories",java.util.List.of(java.util.Map.of("name","app","path",ROOT.resolve("app").toString())),"truncated",false))))).andExpect(status().isNoContent());
        mvc.perform(asyncDispatch(browse)).andExpect(status().isOk()).andExpect(jsonPath("$.directories[0].name").value("app"));
    }
    @AfterAll static void cleanup() throws Exception {try(var paths=Files.walk(ROOT)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
}
