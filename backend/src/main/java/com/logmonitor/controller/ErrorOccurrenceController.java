package com.logmonitor.controller;

import com.logmonitor.model.ApiModels.ErrorOccurrenceDetail;
import com.logmonitor.model.ApiModels.ErrorOccurrencePage;
import com.logmonitor.model.ApiModels.ErrorOccurrenceUpdates;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.ErrorOccurrenceService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/errors/occurrences")
@RequirePermission(AppPermission.MONITOR_READ)
public class ErrorOccurrenceController {
    private final ErrorOccurrenceService service;

    public ErrorOccurrenceController(ErrorOccurrenceService service) {
        this.service = service;
    }

    @GetMapping
    public ErrorOccurrencePage list(@RequestParam(name = "service", required = false) String serviceName,
                                    @RequestParam(required = false) String applicationNamespace,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to,
                                    @RequestParam(required = false) Long agentId,
                                    @RequestParam(required = false) Long sourceId,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "DESC") String sort,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int pageSize) {
        return service.list(namespace(serviceName, applicationNamespace), from, to, sourceId, agentId, category, keyword, sort, page, pageSize);
    }

    @GetMapping("/updates")
    public ErrorOccurrenceUpdates updates(@RequestParam long afterId,
                                          @RequestParam(name = "service", required = false) String serviceName,
                                          @RequestParam(required = false) String applicationNamespace,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to,
                                          @RequestParam(required = false) Long agentId,
                                          @RequestParam(required = false) Long sourceId,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String keyword) {
        return service.updates(afterId, namespace(serviceName, applicationNamespace), from, to, sourceId, agentId, category, keyword);
    }

    @GetMapping("/{id}")
    public ErrorOccurrenceDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    private String namespace(String serviceName, String applicationNamespace) {
        if (serviceName != null && !serviceName.isBlank() && applicationNamespace != null && !applicationNamespace.isBlank()
                && !serviceName.equalsIgnoreCase(applicationNamespace)) throw new IllegalArgumentException("服务参数与应用命名空间冲突");
        return applicationNamespace == null || applicationNamespace.isBlank() ? serviceName : applicationNamespace;
    }
}
