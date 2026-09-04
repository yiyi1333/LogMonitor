package com.logmonitor.controller;

import com.logmonitor.config.LocaleSupport;
import com.logmonitor.model.LlmModels.OccurrenceAnalysisView;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.AuthenticatedUser;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.OccurrenceAiAnalysisService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/errors/occurrences/{occurrenceId}")
public class OccurrenceAiController {
    private final OccurrenceAiAnalysisService service;
    private final LocaleSupport locale;

    public OccurrenceAiController(OccurrenceAiAnalysisService service, LocaleSupport locale) {
        this.service = service;
        this.locale = locale;
    }

    @PostMapping("/ai-analysis")
    @RequirePermission(AppPermission.AI_ANALYZE)
    public OccurrenceAnalysisView request(@PathVariable long occurrenceId,
                                          @RequestParam(defaultValue = "false") boolean refresh,
                                          Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return service.request(occurrenceId, user.id(), user.getUsername(), refresh, locale.tag());
    }

    @GetMapping("/ai-analysis")
    @RequirePermission(AppPermission.MONITOR_READ)
    public OccurrenceAnalysisView current(@PathVariable long occurrenceId, Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return service.current(occurrenceId, user.id(), locale.tag());
    }

    @GetMapping("/ai-analyses")
    @RequirePermission(AppPermission.MONITOR_READ)
    public List<OccurrenceAnalysisView> history(@PathVariable long occurrenceId) {
        return service.history(occurrenceId);
    }
}
