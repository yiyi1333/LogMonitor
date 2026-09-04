package com.logmonitor.model;

import java.time.Instant;

public record CollectorAgent(long id, String agentUuid, String name, String hostName, String displayAddress, String agentVersion,
                             String tokenHash, long configRevision, long spoolBytes, long spoolLimitBytes,
                             Instant lastSeenAt, String lastError, String createdBy, boolean enabled,
                             Instant createdAt, Instant deletedAt) {}
