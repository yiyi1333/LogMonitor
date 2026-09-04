package com.logmonitor.controller;

import com.logmonitor.model.LlmModels.ConnectionTestView;
import com.logmonitor.model.LlmModels.ModelOption;
import com.logmonitor.model.LlmModels.ModelDiscoveryView;
import com.logmonitor.model.LlmModels.ModelImportView;
import com.logmonitor.model.LlmModels.ProviderView;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.LlmConfigurationService;
import com.logmonitor.service.LlmConfigurationService.ConnectionTestCommand;
import com.logmonitor.service.LlmConfigurationService.DiscoveryCommand;
import com.logmonitor.service.LlmConfigurationService.ModelCommand;
import com.logmonitor.service.LlmConfigurationService.ProviderCommand;
import com.logmonitor.service.LlmConfigurationService.SavedDiscoveryCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/llm")
@RequirePermission(AppPermission.LLM_CONFIG_MANAGE)
public class LlmAdminController {
    private final LlmConfigurationService service;

    public LlmAdminController(LlmConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public List<ProviderView> providers() { return service.providers(); }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderView create(@Valid @RequestBody ProviderRequest body, Authentication authentication) {
        return service.createProvider(command(body), authentication.getName());
    }

    @PutMapping("/providers/{id}")
    public ProviderView update(@PathVariable long id, @Valid @RequestBody ProviderRequest body) {
        return service.updateProvider(id, command(body));
    }

    @PatchMapping("/providers/{id}/status")
    public ProviderView status(@PathVariable long id, @Valid @RequestBody StatusRequest body) {
        return service.setProviderEnabled(id, body.enabled());
    }

    @DeleteMapping("/providers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.deleteProvider(id); }

    @PostMapping("/providers/{providerId}/models")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelOption addModel(@PathVariable long providerId, @Valid @RequestBody ModelRequest body) {
        return service.addModel(providerId, modelCommand(body));
    }

    @PutMapping("/providers/{providerId}/models/{modelId}")
    public ModelOption updateModel(@PathVariable long providerId, @PathVariable long modelId,
                                   @Valid @RequestBody ModelRequest body) {
        return service.updateModel(providerId, modelId, modelCommand(body));
    }

    @PatchMapping("/providers/{providerId}/models/{modelId}/status")
    public ModelOption modelStatus(@PathVariable long providerId, @PathVariable long modelId,
                                   @Valid @RequestBody StatusRequest body) {
        return service.setModelEnabled(providerId, modelId, body.enabled());
    }

    @DeleteMapping("/providers/{providerId}/models/{modelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModel(@PathVariable long providerId, @PathVariable long modelId) {
        service.deleteModel(providerId, modelId);
    }

    @PutMapping("/default-model")
    public ModelOption defaultModel(@Valid @RequestBody DefaultModelRequest body) {
        return service.setDefault(body.modelId());
    }

    @PostMapping("/connection-tests")
    public ConnectionTestView test(@RequestBody ConnectionTestRequest body) {
        return service.test(new ConnectionTestCommand(body.providerId(), body.modelConfigId(), body.name(),
                body.providerType(), body.baseUrl(), body.apiKey(), body.modelId()));
    }

    @PostMapping("/model-discovery")
    public ModelDiscoveryView discover(@Valid @RequestBody DiscoveryRequest body) {
        return service.discover(new DiscoveryCommand(body.providerType(), body.baseUrl(), body.apiKey()));
    }

    @PostMapping("/providers/{id}/model-discovery")
    public ModelDiscoveryView discoverSaved(@PathVariable long id,
                                            @RequestBody(required = false) SavedDiscoveryRequest body) {
        return service.discover(id, body == null ? new SavedDiscoveryCommand(null, null)
                : new SavedDiscoveryCommand(body.baseUrl(), body.apiKey()));
    }

    @PostMapping("/providers/{providerId}/models/import")
    public ModelImportView importModels(@PathVariable long providerId,
                                        @Valid @RequestBody ModelImportRequest body) {
        List<ModelCommand> models = body.models() == null ? List.of()
                : body.models().stream().map(item -> new ModelCommand(
                        item.modelId(), item.displayName(), true)).toList();
        return service.importModels(providerId, models);
    }

    private ProviderCommand command(ProviderRequest body) {
        List<ModelCommand> models = body.models() == null ? List.of()
                : body.models().stream().map(this::modelCommand).toList();
        return new ProviderCommand(body.name(), body.providerType(), body.baseUrl(), body.apiKey(),
                body.enabled(), models);
    }

    private ModelCommand modelCommand(ModelRequest body) {
        return new ModelCommand(body.modelId(), body.displayName(), body.enabled());
    }

    public record ProviderRequest(@NotBlank String name, @NotBlank String providerType,
                                  String baseUrl, String apiKey, boolean enabled,
                                  List<@Valid ModelRequest> models) {}
    public record ModelRequest(@NotBlank String modelId, String displayName, boolean enabled) {}
    public record StatusRequest(@NotNull Boolean enabled) {}
    public record DefaultModelRequest(@NotNull Long modelId) {}
    public record ConnectionTestRequest(Long providerId, Long modelConfigId, String name,
                                        String providerType, String baseUrl, String apiKey, String modelId) {}
    public record DiscoveryRequest(@NotBlank String providerType, String baseUrl, String apiKey) {}
    public record SavedDiscoveryRequest(String baseUrl, String apiKey) {}
    public record ModelImportRequest(List<@NotNull @Valid ImportModelRequest> models) {}
    public record ImportModelRequest(@NotBlank String modelId, String displayName) {}
}
