package com.logmonitor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.UserAccount;
import com.logmonitor.config.LocaleSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AccountSessionFilter extends OncePerRequestFilter {
    private final LogMonitorMapper mapper;
    private final ObjectMapper objectMapper;
    private final LocaleSupport locale;

    public AccountSessionFilter(LogMonitorMapper mapper, ObjectMapper objectMapper, LocaleSupport locale) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.locale = locale;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserAccount current = mapper.userAccountById(principal.id());
        if (current != null && current.enabled() && current.sessionVersion() == principal.sessionVersion()) {
            filterChain.doFilter(request, response);
            return;
        }

        String code = current != null && !current.enabled() ? "ACCOUNT_DISABLED" : "SESSION_REVOKED";
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        if (isPublicAuthenticationEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("code", code, "message", locale.error(request, code, null)));
    }

    private boolean isPublicAuthenticationEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "/api/auth/login".equals(path) || "/api/auth/csrf".equals(path) || "/api/auth/logout".equals(path);
    }
}
