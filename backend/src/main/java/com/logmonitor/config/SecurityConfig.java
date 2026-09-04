package com.logmonitor.config;

import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.UserAccount;
import com.logmonitor.security.AuthenticatedUser;
import com.logmonitor.security.AccountSessionFilter;
import com.logmonitor.security.AgentAuthenticationFilter;
import com.logmonitor.service.AccountService;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(LogMonitorMapper mapper) {
        return username -> {
            String canonical = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
            UserAccount account = mapper.userAccount(canonical);
            if (account == null) throw new org.springframework.security.core.userdetails.UsernameNotFoundException(canonical);
            return new AuthenticatedUser(account);
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(List.of(provider));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AccountSessionFilter accountSessionFilter,
                                 AgentAuthenticationFilter agentAuthenticationFilter,
                                 LocaleSupport locale, ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path("/"));
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return http
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler)
                        .ignoringRequestMatchers("/api/agent/v1/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login", "/api/agent/v1/enroll",
                                "/assets/**", "/", "/index.html", "/favicon.ico").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(), java.util.Map.of(
                            "code", "LOGIN_EXPIRED", "message", locale.error(request, "LOGIN_EXPIRED", null)));
                }))
                .addFilterAfter(agentAuthenticationFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(accountSessionFilter, AgentAuthenticationFilter.class)
                .formLogin(config -> config.disable())
                .httpBasic(config -> config.disable())
                .logout(config -> config.disable())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "log-monitor.admin.seed-enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner seedAdmin(AccountService accounts, LogMonitorProperties properties) {
        return (ApplicationArguments args) -> {
            accounts.ensureRootUser(properties.getAdmin().getUsername(), properties.getAdmin().getPassword());
            if ("change-me".equals(properties.getAdmin().getPassword())) {
                log.warn("Default admin password is active; set LOG_MONITOR_ADMIN_PASSWORD before deployment");
            }
        };
    }
}
