package com.github.lechandonga.requesttraceobservabilityjava.observability.metrics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 运行时指标查询接口：按接口维度给出请求量、失败量与耗时分布（含分位数）。
 */
@RestController
@RequestMapping("/observability/metrics")
public class MetricsController {

    private final MetricsRegistry registry;

    public MetricsController(MetricsRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public MetricsView metrics() {
        return new MetricsView(Instant.now(), registry.dimensionCount(), registry.snapshot());
    }

    public record MetricsView(
            Instant timestamp,
            int dimensionCount,
            List<RouteMetrics.Snapshot> routes
    ) {
    }
}
