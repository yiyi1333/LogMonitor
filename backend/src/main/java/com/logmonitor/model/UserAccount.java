package com.logmonitor.model;

import com.logmonitor.security.UserRole;
import java.time.Instant;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        UserRole role,
        boolean enabled,
        boolean mustChangePassword,
        long sessionVersion,
        Instant createdAt
) {}
