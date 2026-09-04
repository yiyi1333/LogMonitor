package com.logmonitor.security;

import com.logmonitor.model.UserAccount;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AuthenticatedUser implements UserDetails, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final String password;
    private final UserRole role;
    private final boolean enabled;
    private final boolean mustChangePassword;
    private final long sessionVersion;

    public AuthenticatedUser(UserAccount account) {
        this.id = account.id();
        this.username = account.username();
        this.password = account.passwordHash();
        this.role = account.role();
        this.enabled = account.enabled();
        this.mustChangePassword = account.mustChangePassword();
        this.sessionVersion = account.sessionVersion();
    }

    public long id() { return id; }
    public UserRole role() { return role; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public long sessionVersion() { return sessionVersion; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
}
