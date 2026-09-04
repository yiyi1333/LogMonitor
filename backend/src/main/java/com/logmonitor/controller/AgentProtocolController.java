package com.logmonitor.controller;

import com.logmonitor.model.ApiModels.AgentBatchAck;
import com.logmonitor.model.ApiModels.AgentBatchMetadata;
import com.logmonitor.model.ApiModels.AgentConfigResponse;
import com.logmonitor.model.ApiModels.AgentEnrollRequest;
import com.logmonitor.model.ApiModels.AgentEnrollResponse;
import com.logmonitor.model.ApiModels.AgentHeartbeatRequest;
import com.logmonitor.security.AgentPrincipal;
import com.logmonitor.service.AgentIngestService;
import com.logmonitor.service.AgentService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/agent/v1")
public class AgentProtocolController {
    private final AgentService agents;
    private final AgentIngestService ingest;

    public AgentProtocolController(AgentService agents, AgentIngestService ingest) {
        this.agents = agents;
        this.ingest = ingest;
    }

    @PostMapping("/enroll")
    public ResponseEntity<AgentEnrollResponse> enroll(@RequestBody AgentEnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agents.enroll(request));
    }

    @GetMapping("/config")
    public ResponseEntity<AgentConfigResponse> configuration(Authentication authentication,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        AgentPrincipal principal = principal(authentication);
        AgentConfigResponse configuration = agents.configuration(principal.id());
        String etag = "\"" + configuration.revision() + "\"";
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        return ResponseEntity.ok().eTag(etag).body(configuration);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(Authentication authentication, @RequestBody AgentHeartbeatRequest request) {
        agents.heartbeat(principal(authentication).id(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/batches", consumes = "multipart/form-data")
    public AgentBatchAck batch(Authentication authentication,
                               @RequestPart("metadata") AgentBatchMetadata metadata,
                               @RequestPart("payload") MultipartFile payload) throws IOException {
        return ingest.accept(principal(authentication), metadata, payload.getInputStream(), payload.getSize());
    }

    private AgentPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            throw new org.springframework.security.authentication.InsufficientAuthenticationException("Agent 凭证无效");
        }
        return principal;
    }
}
