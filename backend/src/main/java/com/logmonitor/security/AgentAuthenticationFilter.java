package com.logmonitor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.model.CollectorAgent;
import com.logmonitor.service.AgentService;
import com.logmonitor.config.LocaleSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AgentAuthenticationFilter extends OncePerRequestFilter {
    private final AgentService agents;
    private final ObjectMapper objectMapper;
    private final LocaleSupport locale;

    public AgentAuthenticationFilter(AgentService agents, ObjectMapper objectMapper, LocaleSupport locale) {
        this.agents = agents;
        this.objectMapper = objectMapper;
        this.locale = locale;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/agent/v1/") || "/api/agent/v1/enroll".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        String authorization = request.getHeader("Authorization");
        CollectorAgent agent = authorization != null && authorization.startsWith("Bearer ")
                ? agents.authenticate(authorization.substring(7)) : null;
        if (agent == null || !agent.enabled()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of("code", "AGENT_REVOKED",
                    "message", locale.error(request, "AGENT_REVOKED", null)));
            return;
        }
        AgentPrincipal principal = new AgentPrincipal(agent.id(), agent.agentUuid(), agent.name());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
