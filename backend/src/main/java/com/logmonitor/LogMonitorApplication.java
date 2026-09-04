package com.logmonitor;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LlmProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@MapperScan("com.logmonitor.mapper")
@EnableConfigurationProperties({LogMonitorProperties.class, LlmProperties.class})
@SpringBootApplication
public class LogMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogMonitorApplication.class, args);
    }
}
