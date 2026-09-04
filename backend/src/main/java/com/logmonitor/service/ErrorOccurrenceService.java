package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.ErrorOccurrenceDetail;
import com.logmonitor.model.ApiModels.ErrorOccurrencePage;
import com.logmonitor.model.ApiModels.ErrorOccurrenceUpdates;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class ErrorOccurrenceService {
    private final LogMonitorMapper mapper;
    private final LogMonitorProperties properties;
    private final QueryService queries;

    public ErrorOccurrenceService(LogMonitorMapper mapper, LogMonitorProperties properties, QueryService queries) {
        this.mapper = mapper;
        this.properties = properties;
        this.queries = queries;
    }

    public ErrorOccurrencePage list(String service, Instant from, Instant to, Long agentId,
                                    String category, String keyword, String sort, int page, int pageSize) {
        return list(service, from, to, null, agentId, category, keyword, sort, page, pageSize);
    }

    public ErrorOccurrencePage list(String service, Instant from, Instant to, Long sourceId, Long agentId,
                                    String category, String keyword, String sort, int page, int pageSize) {
        Query query = normalize(service, from, to, sourceId, agentId, category, keyword, sort, page, pageSize);
        long snapshotId = mapper.latestOccurrenceId();
        return new ErrorOccurrencePage(
                mapper.errorOccurrences(query.service(), query.from(), query.to(), query.instanceKey(), query.sourceId(),
                        query.category(), query.keyword(), query.sort(), snapshotId, query.pageSize(),
                        (query.page() - 1) * query.pageSize()),
                mapper.errorOccurrenceCount(query.service(), query.from(), query.to(), query.instanceKey(), query.sourceId(),
                        query.category(), query.keyword(), snapshotId),
                query.page(), query.pageSize(), snapshotId);
    }

    public ErrorOccurrenceUpdates updates(long afterId, String service, Instant from, Instant to,
                                          Long agentId, String category, String keyword) {
        return updates(afterId, service, from, to, null, agentId, category, keyword);
    }

    public ErrorOccurrenceUpdates updates(long afterId, String service, Instant from, Instant to,
                                          Long sourceId, Long agentId, String category, String keyword) {
        if (afterId < 0) throw new IllegalArgumentException("增量游标无效");
        Query query = normalize(service, from, to, sourceId, agentId, category, keyword, "DESC", 1, 1);
        long snapshotId = mapper.latestOccurrenceId();
        return new ErrorOccurrenceUpdates(
                mapper.errorOccurrenceUpdateCount(afterId, query.service(), query.from(), query.to(),
                        query.instanceKey(), query.sourceId(), query.category(), query.keyword(), snapshotId),
                snapshotId);
    }

    public ErrorOccurrenceDetail detail(long id) {
        ErrorOccurrenceDetail row = mapper.errorOccurrence(id);
        if (row == null) throw new IllegalArgumentException("错误记录不存在");
        return row;
    }

    private Query normalize(String rawService, Instant rawFrom, Instant rawTo, Long sourceId, Long agentId,
                            String rawCategory, String rawKeyword, String rawSort, int page, int pageSize) {
        String service = blankToNull(rawService);
        if (service == null) throw new IllegalArgumentException("请选择服务");
        Instant to = rawTo == null ? Instant.now() : rawTo;
        Instant from = rawFrom == null ? to.minus(24, ChronoUnit.HOURS) : rawFrom;
        if (!from.isBefore(to)) throw new IllegalArgumentException("时间范围无效");
        if (Duration.between(from, to).compareTo(Duration.ofDays(properties.getRetentionDays())) > 0) {
            throw new IllegalArgumentException("查询范围不能超过180天");
        }
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("分页参数无效");
        String sort = rawSort == null ? "DESC" : rawSort.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"ASC".equals(sort) && !"DESC".equals(sort)) throw new IllegalArgumentException("错误排序参数无效");
        String category = blankToNull(rawCategory);
        if (category != null && !"SYSTEM".equals(category) && !"BUSINESS".equals(category)) {
            throw new IllegalArgumentException("请求参数无效");
        }
        QueryService.Selection selection = queries.selection(service, sourceId, agentId);
        return new Query(selection.namespace(), from, to, selection.instanceKey(), selection.sourceId(),
                category, blankToNull(rawKeyword), sort, page, pageSize);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Query(String service, Instant from, Instant to, String instanceKey, Long sourceId, String category,
                         String keyword, String sort, int page, int pageSize) {}
}
