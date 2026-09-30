package com.logmonitor.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.logmonitor.controller.AgentProtocolController;
import com.logmonitor.config.LocaleSupport;
import com.logmonitor.controller.ApiExceptionHandler;
import com.logmonitor.model.ApiModels.AgentBatchMetadata;
import com.logmonitor.security.AgentPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IngestProtocolTest {
    @Test void fullAdmissionResponds503WithRetryAfterAndNeverStartsIngest() throws Exception {
        AgentIngestService ingest=mock(AgentIngestService.class);PipelineMetrics metrics=mock(PipelineMetrics.class);
        IngestAdmission admission=new IngestAdmission(1,new SourceLockRegistry(),metrics);
        var controller=new AgentProtocolController(mock(AgentService.class),ingest,admission,metrics);
        LocaleSupport locale=mock(LocaleSupport.class);when(locale.error(anyString(),anyString())).thenReturn("busy");
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler(locale)).build();
        AgentBatchMetadata metadata=new AgentBatchMetadata("batch",2,"file","generation","/synthetic",0,0,0,Instant.now(),"UTF-8","checksum",true);
        var auth=new UsernamePasswordAuthenticationToken(new AgentPrincipal(1,"test","test"),"unused");
        try(var held=admission.acquire(1)) {
            mvc.perform(multipart("/api/agent/v1/batches")
                    .file(new MockMultipartFile("metadata","","application/json",new ObjectMapper().findAndRegisterModules().writeValueAsBytes(metadata)))
                    .file(new MockMultipartFile("payload","batch.gz","application/gzip",new byte[0])).principal(auth))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"))
                    .andExpect(jsonPath("$.code").value("INGEST_BUSY"));
        }
        verifyNoInteractions(ingest);
    }
}
