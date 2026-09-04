package com.logmonitor.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class SourceLockRegistry {
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lock(long sourceId) {
        return locks.computeIfAbsent(sourceId, ignored -> new ReentrantLock());
    }
}
