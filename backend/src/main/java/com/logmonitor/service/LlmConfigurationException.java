package com.logmonitor.service;

import org.springframework.http.HttpStatus;

public class LlmConfigurationException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public LlmConfigurationException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
