package com.logmonitor.controller;

import com.logmonitor.security.PasswordChangeRequiredException;
import com.logmonitor.service.DuplicateUsernameException;
import com.logmonitor.service.UserNotFoundException;
import com.logmonitor.service.SourceManagementException;
import com.logmonitor.service.AgentProtocolException;
import com.logmonitor.service.LlmConfigurationException;
import com.logmonitor.config.LocaleSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final LocaleSupport locale;

    public ApiExceptionHandler(LocaleSupport locale) {
        this.locale = locale;
    }

    @ExceptionHandler(LlmConfigurationException.class)
    ResponseEntity<Map<String, String>> llmConfiguration(LlmConfigurationException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", locale.error(exception.code(), exception.getMessage())));
    }

    @ExceptionHandler(AgentProtocolException.class)
    ResponseEntity<Map<String, Object>> agentProtocol(AgentProtocolException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.code());
        body.put("message", locale.error(exception.code(), exception.getMessage()));
        if (exception.expectedOffset() != null) body.put("expectedOffset", exception.expectedOffset());
        return ResponseEntity.status(exception.status())
                .headers(headers -> { if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) headers.set("Retry-After", "1"); })
                .body(body);
    }

    @ExceptionHandler(SourceManagementException.class)
    ResponseEntity<Map<String, String>> sourceManagement(SourceManagementException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", locale.error(exception.code(), exception.getMessage())));
    }

    @ExceptionHandler(DisabledException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> disabled(DisabledException exception) {
        return error("ACCOUNT_DISABLED");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> authentication(AuthenticationException exception) { return error("AUTHENTICATION_FAILED"); }

    @ExceptionHandler(PasswordChangeRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> passwordChangeRequired(PasswordChangeRequiredException exception) {
        return error("PASSWORD_CHANGE_REQUIRED");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> accessDenied(AccessDeniedException exception) {
        return error("FORBIDDEN");
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> duplicateUsername(DuplicateUsernameException exception) {
        return error("USERNAME_EXISTS");
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> userNotFound(UserNotFoundException exception) {
        return error("USER_NOT_FOUND");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> validation(MethodArgumentNotValidException exception) { return error("VALIDATION_FAILED"); }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> illegalArgument(IllegalArgumentException exception) {
        String code = switch (exception.getMessage() == null ? "" : exception.getMessage()) {
            case "接口不存在" -> "ENDPOINT_NOT_FOUND";
            case "错误分组不存在" -> "ERROR_GROUP_NOT_FOUND";
            case "错误记录不存在" -> "ERROR_OCCURRENCE_NOT_FOUND";
            case "请选择服务" -> "ERROR_SERVICE_REQUIRED";
            case "错误排序参数无效" -> "ERROR_SORT_INVALID";
            case "时间范围无效" -> "TIME_RANGE_INVALID";
            case "查询范围不能超过180天" -> "TIME_RANGE_TOO_LARGE";
            case "分页参数无效" -> "PAGE_INVALID";
            case "账号不存在" -> "ACCOUNT_NOT_FOUND";
            case "当前密码错误" -> "CURRENT_PASSWORD_INVALID";
            case "新密码不能与当前密码相同" -> "PASSWORD_UNCHANGED";
            default -> "INVALID_REQUEST";
        };
        return Map.of("code", code, "message", locale.error(code, exception.getMessage()));
    }

    private Map<String, String> error(String code) {
        return Map.of("code", code, "message", locale.error(code, null));
    }
}
