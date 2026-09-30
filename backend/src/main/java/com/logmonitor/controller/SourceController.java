package com.logmonitor.controller;

import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.model.ApiModels.CreateLogSourceRequest;
import com.logmonitor.model.ApiModels.BatchLogSourceRequest;
import com.logmonitor.model.ApiModels.NamespaceUpdateRequest;
import com.logmonitor.model.ApiModels.SourceOptions;
import com.logmonitor.model.ApiModels.SourceStatus;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.LogCollectorService;
import com.logmonitor.service.LogSourceService;
import com.logmonitor.service.NamespaceMigrationService;
import com.logmonitor.service.QueryService;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
@RequirePermission(AppPermission.SOURCE_MANAGE)
public class SourceController {
    private final com.logmonitor.service.DirectoryBrowseService directories;
    private final LogSourceService sources;
    private final LogCollectorService collector;
    private final QueryService queries;
    private final NamespaceMigrationService namespaceMigrations;

    public SourceController(LogSourceService sources, LogCollectorService collector, QueryService queries,
                            NamespaceMigrationService namespaceMigrations, com.logmonitor.service.DirectoryBrowseService directories) {
        this.directories = directories;
        this.sources = sources;
        this.collector = collector;
        this.queries = queries;
        this.namespaceMigrations = namespaceMigrations;
    }

    @GetMapping("/status")
    public List<SourceStatus> statuses() {
        return queries.sourceStatuses();
    }

    @GetMapping("/directories")
    public org.springframework.web.context.request.async.DeferredResult<com.logmonitor.model.DirectoryModels.Listing> directories(
            @RequestParam(required=false) Long agentId, @RequestParam(required=false) String path, @RequestParam(required=false) String query) {
        return directories.browse(agentId,path,query);
    }

    @GetMapping("/options")
    public SourceOptions options(@RequestParam(required = false) Long agentId) {
        return sources.options(agentId);
    }

    @PostMapping
    public ResponseEntity<SourceStatus> create(@RequestBody CreateLogSourceRequest request) {
        Source source = sources.create(request.name(), request.applicationNamespace(), request.collectorType(), request.agentId(), request.path(),
                request.include(), request.exclude(), request.startMode());
        if ("LOCAL".equals(source.getCollectorType())) collector.triggerScan(source.getId());
        return ResponseEntity.created(URI.create("/api/sources/" + source.getId()))
                .body(queries.sourceStatus(source.getId()));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<SourceStatus>> createBatch(@RequestBody BatchLogSourceRequest request) {
        List<Source> created = sources.createBatch(request);
        created.stream().filter(source -> "LOCAL".equals(source.getCollectorType()))
                .forEach(source -> collector.triggerScan(source.getId()));
        return ResponseEntity.status(201).body(created.stream().map(source -> queries.sourceStatus(source.getId())).toList());
    }

    @PatchMapping("/{id}/namespace")
    public ResponseEntity<java.util.Map<String, Object>> updateNamespace(@PathVariable long id,
                                                                          @RequestBody NamespaceUpdateRequest request) {
        long migrationId = namespaceMigrations.request(id, request.applicationNamespace());
        return ResponseEntity.accepted().body(java.util.Map.of("migrationId", migrationId,
                "status", migrationId == 0 ? "UNCHANGED" : "PENDING"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        collector.deleteSource(id);
        return ResponseEntity.noContent().build();
    }
}
