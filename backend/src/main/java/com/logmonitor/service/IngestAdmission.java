package com.logmonitor.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Acquired by the controller outside the proxied upload transaction. */
@Component
public class IngestAdmission {
    private final Semaphore permits;
    private final SourceLockRegistry locks;
    private final PipelineMetrics metrics;
    public IngestAdmission(@Value("${log-monitor.ingest-max-inflight:${INGEST_MAX_INFLIGHT:4}}") int maximum,
                           SourceLockRegistry locks, PipelineMetrics metrics) {
        if (maximum < 1 || maximum > 64) throw new IllegalArgumentException("ingest-max-inflight must be 1..64");
        this.permits = new Semaphore(maximum); this.locks = locks; this.metrics = metrics;
    }
    public Lease acquire(long sourceId) {
        if (!permits.tryAcquire()) throw busy();
        ReentrantLock lock = locks.lock(sourceId);
        long start = System.nanoTime();
        if (!lock.tryLock()) { permits.release(); throw busy(); }
        metrics.elapsed("admissionLock", start); metrics.add("inFlight", 1);
        return new Lease(lock);
    }
    private AgentProtocolException busy() {
        metrics.add("ingestRejected", 1);
        return new AgentProtocolException("INGEST_BUSY", HttpStatus.SERVICE_UNAVAILABLE, "采集处理繁忙，请稍后重试");
    }
    public final class Lease implements AutoCloseable {
        private final ReentrantLock lock;
        private boolean closed;
        private Lease(ReentrantLock lock) { this.lock = lock; }
        public void close() {
            if (!closed) { closed = true; lock.unlock(); permits.release(); metrics.add("inFlight", -1); }
        }
    }
}
