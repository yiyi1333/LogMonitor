package com.logmonitor;

import static org.assertj.core.api.Assertions.*;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.Checkpoint;
import com.logmonitor.model.LogModels.*;
import com.logmonitor.service.LogStorageService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"log-monitor.admin.seed-enabled=false","log-monitor.initial-delay-ms=600000"})
class BulkStorageIntegrationTest extends IntegrationTestSupport {
    @Autowired LogStorageService storage;
    @Autowired LogMonitorMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private final Instant at=Instant.parse("2026-09-30T00:00:00Z");
    @Test void splitsLargeBatchesAndDuplicateReplayDoesNotIncrement() {
        Source source=source(); List<AccessEvent> accesses=new ArrayList<>();List<ErrorEvent> errors=new ArrayList<>();
        for(int i=0;i<1101;i++){accesses.add(new AccessEvent(source.getName(),"/batch",at,UUID.randomUUID().toString()));errors.add(error(source,source.getName(),UUID.randomUUID().toString(),"stack"));}
        Checkpoint checkpoint=checkpoint(source);ParsedBatch batch=new ParsedBatch(accesses,errors,0,at);
        storage.store(batch,checkpoint,false);storage.store(batch,checkpoint,true);
        assertThat(jdbc.queryForObject("SELECT SUM(access_count) FROM api_access_minute WHERE source_id=?",Long.class,source.getId())).isEqualTo(1101);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_occurrence WHERE source_id=?",Long.class,source.getId())).isEqualTo(1101);
        assertThat(jdbc.queryForObject("SELECT occurrence_count FROM error_group WHERE fingerprint=?",Long.class,source.getName())).isEqualTo(1101);
    }
    @Test void laterConstraintFailureRollsBackDedupAggregatesGroupsAndCheckpoint() {
        Source source=source();String accessKey=UUID.randomUUID().toString();
        ErrorEvent invalid=error(source,source.getName(),"x".repeat(100),"stack");
        assertThatThrownBy(()->storage.store(new ParsedBatch(List.of(new AccessEvent(source.getName(),"/rollback",at,accessKey)),List.of(invalid),0,at),checkpoint(source),false))
                .isInstanceOf(RuntimeException.class);
        assertThat(mapper.accessEventExists(accessKey)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_access_minute WHERE source_id=?",Long.class,source.getId())).isZero();
        assertThat(mapper.findErrorGroupByFingerprint(source.getName())).isNull();
        assertThat(mapper.findCheckpoint(source.getId(),"file")).isNull();
    }
    @Test void twoSourcesAtomicallyAccumulateOneSharedFingerprint() throws Exception {
        Source first=source(),second=source();String fingerprint=UUID.randomUUID().toString();
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> results=new ArrayList<>();
            for(Source source:List.of(first,second))results.add(workers.submit(()->{
                try{start.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                ParsedBatch batch=new ParsedBatch(List.of(),List.of(error(source,fingerprint,UUID.randomUUID().toString(),"stack")),0,at);
                // H2 MERGE may conflict on simultaneous first insert. Retry only the rolled-back original batch,
                // exactly as the Agent does after the controller's 503 response.
                try {storage.store(batch,checkpoint(source),false);}
                catch(org.springframework.dao.DuplicateKeyException conflict) {
                    try(var connection=jdbc.getDataSource().getConnection()) {
                        if(!connection.getMetaData().getDatabaseProductName().equals("H2"))throw conflict;
                    }catch(java.sql.SQLException failure){throw new RuntimeException(failure);}
                    storage.store(batch,checkpoint(source),false);
                }
            }));
            start.countDown();for(Future<?> result:results)result.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT occurrence_count FROM error_group WHERE fingerprint=?",Long.class,fingerprint)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_occurrence WHERE group_id=(SELECT id FROM error_group WHERE fingerprint=?)",Long.class,fingerprint)).isEqualTo(2);
        }finally{workers.shutdownNow();}
    }
    private Source source(){
        Source source=new Source();String name=UUID.randomUUID().toString();source.setName(name);source.setApplicationNamespace(name);
        source.setPath("/synthetic/"+name);source.setRealPath(source.getPath());source.setRealPathHash(name);mapper.insertLogSource(source);return source;
    }
    private ErrorEvent error(Source source,String fingerprint,String eventKey,String stack){return new ErrorEvent(source.getName(),"SYSTEM","TestException","synthetic",at,"thread","synthetic",stack,null,"NONE","synthetic",0,fingerprint,fingerprint,eventKey);}
    private Checkpoint checkpoint(Source source){return new Checkpoint(source.getName(),"local",source.getId(),"file","generation","synthetic",1,1,"","","ACTIVE",1,at,at,at,null,0);}
}
