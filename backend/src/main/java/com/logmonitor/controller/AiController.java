package com.logmonitor.controller;

import com.logmonitor.model.LlmModels.AnalysisView;
import com.logmonitor.security.AuthenticatedUser;
import com.logmonitor.service.AiAnalysisService;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.config.LocaleSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import java.util.List;

@RestController
@RequestMapping("/api/errors/groups/{groupId}")
public class AiController {
    private final AiAnalysisService service;
    private final LocaleSupport locale;
    public AiController(AiAnalysisService service, LocaleSupport locale) { this.service = service; this.locale = locale; }

    @PostMapping("/ai-analysis")
    @RequirePermission(AppPermission.AI_ANALYZE)
    public AnalysisView request(@PathVariable long groupId,
                                @RequestParam(defaultValue = "false") boolean refresh,
                                Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return service.request(groupId, user.id(), user.getUsername(), refresh, locale.tag());
    }

    @GetMapping("/ai-analysis")
    @RequirePermission(AppPermission.MONITOR_READ)
    public AnalysisView current(@PathVariable long groupId, Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return service.current(groupId, user.id(), locale.tag());
    }

    @GetMapping("/ai-analyses")
    @RequirePermission(AppPermission.MONITOR_READ)
    public List<AnalysisView> history(@PathVariable long groupId) { return service.history(groupId); }
}
