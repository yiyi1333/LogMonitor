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
    private final com.logmonitor.service.DirectoryBrowseService directories;
    private final AgentService agents;
    private final AgentIngestService ingest;
    private final com.logmonitor.service.IngestAdmission admission;
    private final com.logmonitor.service.PipelineMetrics metrics;

    public AgentProtocolController(AgentService agents, AgentIngestService ingest,
            com.logmonitor.service.IngestAdmission admission, com.logmonitor.service.PipelineMetrics metrics,
            com.logmonitor.service.DirectoryBrowseService directories) {
        this.directories = directories;
        this.agents = agents;
        this.ingest = ingest; this.admission = admission; this.metrics = metrics;
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

    @GetMapping("/directories/requests")
    public org.springframework.web.context.request.async.DeferredResult<ResponseEntity<com.logmonitor.model.DirectoryModels.Request>> directoryRequests(Authentication authentication) {
        return directories.poll(principal(authentication).id());
    }

    @PostMapping("/directories/results")
    public ResponseEntity<Void> directoryResults(Authentication authentication,@RequestBody com.logmonitor.model.DirectoryModels.Result result) {
        directories.complete(principal(authentication).id(),result);return ResponseEntity.noContent().build();
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
        AgentPrincipal agent = principal(authentication);
        if (metadata == null) throw new IllegalArgumentException("批次元数据无效");
        try (var lease = admission.acquire(metadata.sourceId()); var input = payload.getInputStream()) {
            metrics.add("compressedBytes", payload.getSize());
            return ingest.accept(agent, metadata, input, payload.getSize());
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new com.logmonitor.service.AgentProtocolException("INGEST_RETRYABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "采集事务失败，请重试原批次");
        }
    }

    private AgentPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            throw new org.springframework.security.authentication.InsufficientAuthenticationException("Agent 凭证无效");
        }
        return principal;
    }
}
