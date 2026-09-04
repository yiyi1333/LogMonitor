package com.logmonitor.service;

public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException() {
        super("用户名已存在");
    }
}
