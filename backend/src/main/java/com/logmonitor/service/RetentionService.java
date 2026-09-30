package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionService {
    private static final Logger LOG=LoggerFactory.getLogger(RetentionService.class);
    private static final int BATCH=1000;
    private final LogMonitorProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PipelineMetrics metrics;
    private final AtomicBoolean running=new AtomicBoolean();
    public RetentionService(LogMonitorProperties properties,JdbcTemplate jdbc,
                            PlatformTransactionManager manager,PipelineMetrics metrics) {
        this.properties=properties;this.jdbc=jdbc;this.metrics=metrics;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Scheduled(cron="0 15 3 * * *",zone="Asia/Shanghai")
    public void clean() {
        if(!running.compareAndSet(false,true)) {metrics.add("retentionSkipped",1);return;}
        Instant cutoff=Instant.now().minus(properties.getRetentionDays(),ChronoUnit.DAYS);
        long started=System.nanoTime();long deleted=0;
        try {
            deleted+=drain(()->deleteSimple("error_occurrence","id","occurred_at",cutoff));
            deleted+=drain(()->deleteGroups(cutoff));
            deleted+=drain(()->deleteSimple("api_access_minute","id","minute_at",cutoff));
            deleted+=drain(()->deleteSimple("access_event_dedup","event_key","occurred_at",cutoff));
            deleted+=drain(()->deleteBaselines(cutoff));
            deleted+=drain(()->deleteSimple("agent_ingest_batch","id","received_at",cutoff));
            LOG.info("{\"type\":\"retention_complete\",\"deletedRows\":{}}",deleted);
        } catch(Exception failure) {
            metrics.add("retentionFailures",1);
            LOG.warn("{\"type\":\"retention_failed\",\"exception\":\"{}\"}",failure.getClass().getSimpleName());
        } finally {metrics.elapsed("retention",started);running.set(false);}
    }
    private long drain(java.util.function.IntSupplier next) throws InterruptedException {
        long total=0;
        while(true) {
            int count=transaction.execute(status->next.getAsInt());
            if(count==0)return total;
            total+=count;metrics.add("retentionDeletedRows",count);metrics.add("retentionBatches",1);
            LOG.info("{\"type\":\"retention_progress\",\"deletedRows\":{}}",count);
            // Database connection and row locks have been released before throttling.
            try {Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        }
    }
    private int deleteSimple(String table,String key,String time,Instant cutoff) {
        List<Object> ids=jdbc.query("SELECT "+key+" FROM "+table+" WHERE "+time+"<? ORDER BY "+time+","+key+" LIMIT "+BATCH+" FOR UPDATE",
                (rs,row)->rs.getObject(1),Timestamp.from(cutoff));
        if(ids.isEmpty())return 0;
        if(table.equals("error_occurrence")) {
            List<Object> ai=jdbc.query("SELECT id FROM error_occurrence_ai_analysis WHERE occurrence_id IN ("+
                    placeholders(ids.size())+") ORDER BY id LIMIT "+BATCH,(rs,row)->rs.getObject(1),ids.toArray());
            int deleted=ai.isEmpty()?0:jdbc.update("DELETE FROM error_occurrence_ai_analysis WHERE id IN ("+placeholders(ai.size())+")",ai.toArray());
            if(deleted==BATCH)return deleted;
            List<Object> empty=jdbc.query("SELECT id FROM error_occurrence WHERE id IN ("+placeholders(ids.size())+") " +
                    "AND NOT EXISTS (SELECT 1 FROM error_occurrence_ai_analysis a WHERE a.occurrence_id=error_occurrence.id) " +
                    "ORDER BY occurred_at,id LIMIT "+(BATCH-deleted),(rs,row)->rs.getObject(1),ids.toArray());
            if(!empty.isEmpty())deleted+=jdbc.update("DELETE FROM error_occurrence WHERE id IN ("+placeholders(empty.size())+")",empty.toArray());
            return deleted;
        }
        Object[] args=new Object[ids.size()+1];for(int i=0;i<ids.size();i++)args[i]=ids.get(i);args[ids.size()]=Timestamp.from(cutoff);
        return jdbc.update("DELETE FROM "+table+" WHERE "+key+" IN ("+placeholders(ids.size())+") AND "+time+"<?",args);
    }
    private int deleteGroups(Instant cutoff) {
        // Lock empty expired parents in the same fingerprint order as uploads.
        List<Long> ids=jdbc.query("SELECT id FROM error_group WHERE last_seen<? AND NOT EXISTS " +
                "(SELECT 1 FROM error_occurrence o WHERE o.group_id=error_group.id) ORDER BY fingerprint LIMIT "+BATCH+" FOR UPDATE",
                (rs,row)->rs.getLong(1),Timestamp.from(cutoff));
        if(ids.isEmpty())return 0;
        String in=placeholders(ids.size());Object[] args=ids.toArray();
        List<Long> ai=jdbc.query("SELECT id FROM ai_analysis WHERE group_id IN ("+in+") ORDER BY id LIMIT "+BATCH,
                (rs,row)->rs.getLong(1),args);
        int deleted=ai.isEmpty()?0:jdbc.update("DELETE FROM ai_analysis WHERE id IN ("+placeholders(ai.size())+")",ai.toArray());
        int remaining=BATCH-deleted;
        if(remaining==0)return deleted;
        List<Long> empty=jdbc.query("SELECT id FROM error_group WHERE id IN ("+in+") AND NOT EXISTS " +
                "(SELECT 1 FROM ai_analysis a WHERE a.group_id=error_group.id) AND NOT EXISTS " +
                "(SELECT 1 FROM error_occurrence o WHERE o.group_id=error_group.id) ORDER BY fingerprint LIMIT "+remaining,
                (rs,row)->rs.getLong(1),args);
        if(!empty.isEmpty())deleted+=jdbc.update("DELETE FROM error_group WHERE id IN ("+placeholders(empty.size())+")",empty.toArray());
        return deleted;
    }
    private int deleteBaselines(Instant cutoff) {
        List<Object[]> keys=jdbc.query("SELECT source_id,uri_hash,minute_at FROM access_dedup_baseline WHERE minute_at<? " +
                "ORDER BY minute_at,source_id,uri_hash LIMIT "+BATCH,
                (rs,row)->new Object[]{rs.getObject(1),rs.getString(2),rs.getTimestamp(3)},Timestamp.from(cutoff));
        int deleted=0;
        for(Object[] key:keys)deleted+=jdbc.update("DELETE FROM access_dedup_baseline WHERE source_id=? AND uri_hash=? AND minute_at=?",key);
        return deleted;
    }
    private String placeholders(int count) {return String.join(",",java.util.Collections.nCopies(count,"?"));}
}
