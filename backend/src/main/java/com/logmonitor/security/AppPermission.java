package com.logmonitor.security;

import java.util.EnumSet;
import java.util.Set;

public enum AppPermission {
    MONITOR_READ(UserRole.ROOT, UserRole.USER),
    SOURCE_MANAGE(UserRole.ROOT, UserRole.USER),
    AGENT_MANAGE(UserRole.ROOT, UserRole.USER),
    AI_ANALYZE(UserRole.ROOT, UserRole.USER),
    LLM_PREFERENCE(UserRole.ROOT, UserRole.USER),
    CHANGE_OWN_PASSWORD(UserRole.ROOT, UserRole.USER),
    LLM_CONFIG_MANAGE(UserRole.ROOT),
    USER_MANAGE(UserRole.ROOT);

    private final Set<UserRole> roles;

    AppPermission(UserRole first, UserRole... rest) {
        this.roles = EnumSet.of(first, rest);
    }

    public boolean allows(UserRole role) {
        return roles.contains(role);
    }
}
