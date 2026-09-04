package com.logmonitor.controller;

import com.logmonitor.mapper.LogMonitorMapper;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final LogMonitorMapper mapper;

    public HealthController(LogMonitorMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "database", mapper.databasePing() == 1 ? "UP" : "DOWN");
    }
}
