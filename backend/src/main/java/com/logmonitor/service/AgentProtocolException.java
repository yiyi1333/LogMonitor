package com.logmonitor.service;

import org.springframework.http.HttpStatus;

public class AgentProtocolException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Long expectedOffset;

    public AgentProtocolException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public AgentProtocolException(String code, HttpStatus status, String message, Long expectedOffset) {
        super(message);
        this.code = code;
        this.status = status;
        this.expectedOffset = expectedOffset;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public Long expectedOffset() { return expectedOffset; }
}
