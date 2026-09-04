package com.logmonitor.security;

public class PasswordChangeRequiredException extends RuntimeException {
    public PasswordChangeRequiredException() {
        super("请先修改初始密码");
    }
}
