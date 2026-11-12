package com.localllm.locie.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final SystemMetricsService metrics;

    public MetricsController(SystemMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public MetricsResponse metrics() {
        return metrics.snapshot();
    }
}
