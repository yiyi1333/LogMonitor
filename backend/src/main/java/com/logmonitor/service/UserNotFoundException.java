package com.logmonitor.service;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException() {
        super("普通用户不存在");
    }
}
