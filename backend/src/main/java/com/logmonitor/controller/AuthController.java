package com.logmonitor.controller;

import com.logmonitor.model.ApiModels.SessionUser;
import com.logmonitor.security.AppPermission;
import com.logmonitor.security.AuthenticatedUser;
import com.logmonitor.security.RequirePermission;
import com.logmonitor.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.csrf.CsrfToken;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final AccountService accounts;
    public AuthController(AuthenticationManager authenticationManager, AccountService accounts) {
        this.authenticationManager = authenticationManager;
        this.accounts = accounts;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public SessionUser login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(false);
        if (session == null) session = request.getSession(true);
        else request.changeSessionId();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return sessionUser(authentication);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    @GetMapping("/me")
    public SessionUser me(Authentication authentication) { return sessionUser(authentication); }

    @PostMapping("/password")
    @RequirePermission(AppPermission.CHANGE_OWN_PASSWORD)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body, Authentication authentication,
                               HttpServletRequest request, HttpServletResponse response) {
        accounts.changePassword(authentication.getName(), body.currentPassword(), body.newPassword());
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    private SessionUser sessionUser(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return new SessionUser(user.getUsername(), user.role().name(), user.mustChangePassword());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
}
