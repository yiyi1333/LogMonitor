package com.logmonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "log-monitor.llm")
public class LlmProperties {
    private String masterKey;
    private boolean allowHttp;
    private int timeoutSeconds = 120;
    private int testTimeoutSeconds = 15;
    private int maxRetries = 2;
    private int maxOutputTokens = 2048;

    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public boolean isAllowHttp() { return allowHttp; }
    public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getTestTimeoutSeconds() { return testTimeoutSeconds; }
    public void setTestTimeoutSeconds(int testTimeoutSeconds) { this.testTimeoutSeconds = testTimeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
}
