package com.github.lechandonga.requesttraceobservabilityjava.observability.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个接口维度（method + 路由模板）的统计：请求量、分错误码失败量、延迟分布。
 */
public class RouteMetrics {

    private final String method;
    private final String route;
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final Map<String, LongAdder> failuresByCode = new ConcurrentHashMap<>();
    private final LatencyHistogram latencyHistogram = new LatencyHistogram();

    public RouteMetrics(String method, String route) {
        this.method = method;
        this.route = route;
    }

    public void record(long latencyMs, boolean failed, String errorCode) {
        totalRequests.increment();
        latencyHistogram.record(latencyMs);
        if (failed) {
            failedRequests.increment();
            if (errorCode != null) {
                failuresByCode.computeIfAbsent(errorCode, key -> new LongAdder()).increment();
            }
        }
    }

    public Snapshot snapshot() {
        long total = totalRequests.sum();
        long failed = failedRequests.sum();
        Map<String, Long> byCode = new ConcurrentHashMap<>();
        failuresByCode.forEach((code, adder) -> byCode.put(code, adder.sum()));
        return new Snapshot(method, route, total, failed,
                total == 0 ? 0d : Math.round((double) failed / total * 10000d) / 100d,
                latencyHistogram.snapshot(), byCode);
    }

    public record Snapshot(
            String method,
            String route,
            long totalRequests,
            long failedRequests,
            double failureRatePercent,
            LatencyHistogram.Snapshot latency,
            Map<String, Long> failuresByCode
    ) {
    }
}
