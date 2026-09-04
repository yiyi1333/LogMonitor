package com.logmonitor.controller;

import com.logmonitor.model.LlmModels.SettingsView;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.AuthenticatedUser;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.LlmConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/llm")
@RequirePermission(AppPermission.LLM_PREFERENCE)
public class LlmSettingsController {
    private final LlmConfigurationService service;

    public LlmSettingsController(LlmConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsView settings(Authentication authentication) {
        return service.settings(user(authentication).id());
    }

    @PutMapping("/preference")
    public SettingsView preference(@Valid @RequestBody PreferenceRequest body, Authentication authentication) {
        return service.setPreference(user(authentication).id(), body.modelId());
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public record PreferenceRequest(@NotNull Long modelId) {}
}
