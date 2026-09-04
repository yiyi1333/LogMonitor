package com.logmonitor.controller;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.EndpointRow;
import com.logmonitor.model.ApiModels.ErrorGroupRow;
import com.logmonitor.model.ApiModels.ErrorOccurrenceRow;
import com.logmonitor.model.ApiModels.PageResult;
import com.logmonitor.model.ApiModels.TimePoint;
import com.logmonitor.model.ApiModels.ApplicationOption;
import com.logmonitor.service.QueryService;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.RequirePermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequirePermission(AppPermission.MONITOR_READ)
public class MonitorController {
    private final QueryService queries;
    private final LogMonitorMapper mapper;
    public MonitorController(QueryService queries, LogMonitorMapper mapper) { this.queries = queries; this.mapper = mapper; }

    @GetMapping("/dashboard/summary")
    public Map<String,Object> dashboard(@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String service,@RequestParam(required=false) String applicationNamespace,
            @RequestParam(required=false) Long sourceId,@RequestParam(required=false) Long agentId) {
        Instant end = to == null ? Instant.now() : to;
        return queries.dashboard(from == null ? end.minus(30, ChronoUnit.DAYS) : from, end,
                namespace(service, applicationNamespace), sourceId, agentId);
    }

    @GetMapping("/endpoints")
    public PageResult<EndpointRow> endpoints(@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String service,@RequestParam(required=false) String applicationNamespace,
            @RequestParam(required=false) Long sourceId,@RequestParam(required=false) Long agentId,
            @RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        Instant end = to == null ? Instant.now() : to;
        return queries.endpoints(from == null ? end.minus(30,ChronoUnit.DAYS) : from,end,
                namespace(service, applicationNamespace),sourceId,agentId,keyword,page,pageSize);
    }

    @GetMapping("/endpoints/{id}/trend")
    public List<TimePoint> endpointTrend(@PathVariable long id,@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,@RequestParam(required=false) Long sourceId,@RequestParam(required=false) Long agentId) {
        Instant end = to == null ? Instant.now() : to;
        return queries.endpointTrend(id,from == null ? end.minus(30,ChronoUnit.DAYS) : from,end,sourceId,agentId);
    }

    @GetMapping("/errors/groups")
    public PageResult<ErrorGroupRow> errorGroups(@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) String service,@RequestParam(required=false) String applicationNamespace,
            @RequestParam(required=false) Long sourceId,@RequestParam(required=false) Long agentId,
            @RequestParam(required=false) String category,@RequestParam(required=false) String keyword,
            @RequestParam(required=false) String endpoint,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        Instant end = to == null ? Instant.now() : to;
        return queries.errors(from == null ? end.minus(30,ChronoUnit.DAYS) : from,end,
                namespace(service, applicationNamespace),sourceId,agentId,category,keyword,endpoint,page,pageSize);
    }

    @GetMapping("/errors/groups/{id}")
    public ErrorGroupRow errorGroup(@PathVariable long id) {
        ErrorGroupRow row = mapper.errorGroup(id);
        if (row == null) throw new IllegalArgumentException("错误分组不存在");
        return row;
    }

    @GetMapping("/errors/groups/{id}/occurrences")
    public PageResult<ErrorOccurrenceRow> occurrences(@PathVariable long id,@RequestParam(required=false) Long sourceId,
            @RequestParam(required=false) Long agentId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
        String instanceKey = agentId == null ? null : queries.agentInstanceKey(agentId);
        return new PageResult<>(mapper.occurrences(id,instanceKey,sourceId,pageSize,(page-1)*pageSize),
                mapper.occurrenceCount(id,instanceKey,sourceId),page,pageSize);
    }

    @GetMapping("/services")
    public List<String> services() { return mapper.services(); }

    @GetMapping("/applications/options")
    public List<ApplicationOption> applicationOptions() { return queries.applicationOptions(); }

    private String namespace(String service, String applicationNamespace) {
        if (service != null && !service.isBlank() && applicationNamespace != null && !applicationNamespace.isBlank()
                && !service.equalsIgnoreCase(applicationNamespace)) {
            throw new IllegalArgumentException("服务参数与应用命名空间冲突");
        }
        return applicationNamespace == null || applicationNamespace.isBlank() ? service : applicationNamespace;
    }
}
