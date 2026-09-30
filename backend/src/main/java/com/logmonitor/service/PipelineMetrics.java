package com.logmonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed metric names only: never use source paths, event keys or credentials as labels. */
@Component
public class PipelineMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineMetrics.class);
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final DataSource dataSource;
    public PipelineMetrics(ObjectMapper json, DataSource dataSource) { this.json = json; this.dataSource = dataSource; }
    public void add(String name, long value) { counters.computeIfAbsent(name, ignored -> new LongAdder()).add(value); }
    public void elapsed(String name, long start) { add(name + "Nanos", System.nanoTime() - start); add(name + "Count", 1); }
    public Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", "pipeline_metrics");
        counters.forEach((name, value) -> values.put(name, value.sum()));
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        values.put("heapUsedBytes", memory.getUsed()); values.put("heapMaxBytes", memory.getMax());
        long gcMillis = 0; long gcCount = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcMillis += Math.max(0, gc.getCollectionTime()); gcCount += Math.max(0, gc.getCollectionCount());
        }
        values.put("gcMillis", gcMillis); values.put("gcCount", gcCount);
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            var pool = hikari.getHikariPoolMXBean();
            values.put("dbActive", pool.getActiveConnections()); values.put("dbIdle", pool.getIdleConnections());
            values.put("dbWaiting", pool.getThreadsAwaitingConnection());
        }
        return values;
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void report() {
        try { LOG.info("{}", json.writeValueAsString(snapshot())); }
        catch (Exception ignored) { LOG.warn("Pipeline metric serialization failed"); }
    }
}
