package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.mapper.LogMonitorMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private final LogMonitorMapper mapper;
    private final LogMonitorProperties properties;
    private final com.logmonitor.mapper.AgentMapper agentMapper;
    public RetentionService(LogMonitorMapper mapper, LogMonitorProperties properties,
                            com.logmonitor.mapper.AgentMapper agentMapper) {
        this.mapper = mapper; this.properties = properties; this.agentMapper = agentMapper;
    }
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void clean() {
        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        mapper.deleteOldAi(cutoff);
        mapper.deleteOldOccurrences(cutoff);
        mapper.deleteOldGroups(cutoff);
        mapper.deleteOldAccess(cutoff);
        mapper.deleteOldAccessDedup(cutoff);
        mapper.deleteOldAccessBaseline(cutoff);
        agentMapper.deleteOldBatches(cutoff);
    }
}
