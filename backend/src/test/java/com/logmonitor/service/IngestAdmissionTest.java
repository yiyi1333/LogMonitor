package com.logmonitor.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class IngestAdmissionTest {
    @Test void rejectsBeforeWorkAndReturnsPermitAfterExceptionOrClose() throws Exception {
        SourceLockRegistry locks=new SourceLockRegistry();PipelineMetrics metrics=mock(PipelineMetrics.class);
        IngestAdmission admission=new IngestAdmission(1,locks,metrics);
        try(var lease=admission.acquire(1)) {
            assertThatThrownBy(()->admission.acquire(2)).isInstanceOf(AgentProtocolException.class)
                    .satisfies(e->assertThat(((AgentProtocolException)e).code()).isEqualTo("INGEST_BUSY"));
        }
        try(var lease=admission.acquire(2)) {assertThat(locks.lock(2).isHeldByCurrentThread()).isTrue();}
        verify(metrics).add("ingestRejected",1);
    }
    @Test void rejectsBusySourceWithoutConsumingGlobalCapacity() throws Exception {
        SourceLockRegistry locks=new SourceLockRegistry();IngestAdmission admission=new IngestAdmission(2,locks,mock(PipelineMetrics.class));
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);
        Future<?> held=worker.submit(()->{locks.lock(1).lock();try{ready.countDown();release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{locks.lock(1).unlock();}});
        try {
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->admission.acquire(1)).isInstanceOf(AgentProtocolException.class);
            try(var first=admission.acquire(2);var second=admission.acquire(3)) {assertThat(locks.lock(3).isHeldByCurrentThread()).isTrue();}
        }finally{release.countDown();held.get(2,TimeUnit.SECONDS);worker.shutdownNow();}
    }
}
