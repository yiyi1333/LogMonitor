package com.logmonitor;

import static org.assertj.core.api.Assertions.*;
import com.logmonitor.service.RetentionService;
import com.logmonitor.service.PipelineMetrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"log-monitor.admin.seed-enabled=false","log-monitor.initial-delay-ms=600000"})
class RetentionIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionService retention;
    @Autowired PipelineMetrics metrics;
    @Test void removesExpiredDataAcrossMultipleTransactionsAndKeepsLiveGroupHistory() {
        Timestamp old=Timestamp.from(Instant.now().minus(181,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));Timestamp now=Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        for(int i=0;i<2005;i++)jdbc.update("INSERT INTO access_event_dedup(event_key,occurred_at) VALUES(?,?)",UUID.randomUUID().toString(),old);
        String liveKey=UUID.randomUUID().toString();jdbc.update("INSERT INTO access_event_dedup(event_key,occurred_at) VALUES(?,?)",liveKey,now);
        String oldFingerprint=UUID.randomUUID().toString(),liveFingerprint=UUID.randomUUID().toString();
        for(String key:new String[]{oldFingerprint,liveFingerprint})jdbc.update("INSERT INTO error_group(fingerprint,signature_hash,service_name,category,summary,first_seen,last_seen,occurrence_count) VALUES(?,?,'retention','SYSTEM','synthetic',?,?,1)",key,key,old,key.equals(oldFingerprint)?old:now);
        long oldId=jdbc.queryForObject("SELECT id FROM error_group WHERE fingerprint=?",Long.class,oldFingerprint);
        for(int i=0;i<1005;i++)jdbc.update("INSERT INTO ai_analysis(group_id,provider_name,provider_type,model_name,prompt_version,status) VALUES(?,'test','test','test','test','SUCCESS')",oldId);
        retention.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_event_dedup WHERE occurred_at<?",Long.class,old)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_event_dedup WHERE event_key=?",Long.class,liveKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_group WHERE fingerprint=?",Long.class,oldFingerprint)).isZero();
        assertThat(jdbc.queryForObject("SELECT first_seen FROM error_group WHERE fingerprint=?",Timestamp.class,liveFingerprint)).isEqualTo(old);
        assertThat(((Number)metrics.snapshot().get("retentionBatches")).longValue()).isGreaterThanOrEqualTo(5);
        retention.clean();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_group WHERE fingerprint=?",Long.class,liveFingerprint)).isEqualTo(1);
    }
}
