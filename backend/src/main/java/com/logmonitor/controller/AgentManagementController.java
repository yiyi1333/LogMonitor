package com.logmonitor.controller;

import com.logmonitor.model.ApiModels.AgentSummary;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.AgentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
@RequirePermission(AppPermission.AGENT_MANAGE)
public class AgentManagementController {
    private final AgentService agents;

    public AgentManagementController(AgentService agents) {
        this.agents = agents;
    }

    @GetMapping
    public List<AgentSummary> list() {
        return agents.summaries();
    }

    @GetMapping("/{id}")
    public AgentSummary detail(@PathVariable long id) {
        return agents.summary(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable long id) {
        agents.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
