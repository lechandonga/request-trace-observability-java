package com.github.lechandonga.requesttraceobservabilityjava.observability.metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标注册表：按 method+路由模板聚合，标签总数有硬上限，保证内存有界。
 *
 * <p>超过上限后，新出现的维度一律归入 {@code (method, unknown)} 兜底桶；
 * 所有计数基于 {@link java.util.concurrent.atomic.LongAdder}，并发更新不丢数。
 */
@Component
public class MetricsRegistry {

    private static final String FALLBACK_DIMENSION = "unknown";

    private final Map<String, RouteMetrics> routes = new ConcurrentHashMap<>();
    private final int maxDimensions;

    public MetricsRegistry(@Value("${observability.metrics.max-dimensions:200}") int maxDimensions) {
        this.maxDimensions = maxDimensions > 0 ? maxDimensions : 200;
    }

    public void record(String method, String route, long latencyMs, boolean failed, String errorCode) {
        try {
            String dimensionKey = method + ' ' + route;
            RouteMetrics metrics = routes.get(dimensionKey);
            if (metrics == null) {
                metrics = resolveOrCreate(method, route, dimensionKey);
            }
            metrics.record(latencyMs, failed, errorCode);
        } catch (RuntimeException e) {
            // 观测自身故障：静默降级，不影响业务请求
        }
    }

    private RouteMetrics resolveOrCreate(String method, String route, String dimensionKey) {
        String targetRoute = route;
        String targetKey = dimensionKey;
        if (!RouteKeyResolver.UNMATCHED_ROUTE.equals(route)
                && !RouteKeyResolver.UNKNOWN_ROUTE.equals(route)
                && routes.size() >= maxDimensions
                && !routes.containsKey(dimensionKey)) {
            targetRoute = FALLBACK_DIMENSION;
            targetKey = method + ' ' + FALLBACK_DIMENSION;
        }
        String finalRoute = targetRoute;
        return routes.computeIfAbsent(targetKey, key -> new RouteMetrics(method, finalRoute));
    }

    public List<RouteMetrics.Snapshot> snapshot() {
        List<RouteMetrics.Snapshot> result = new ArrayList<>(routes.size());
        for (RouteMetrics metrics : routes.values()) {
            result.add(metrics.snapshot());
        }
        result.sort(java.util.Comparator
                .comparing((RouteMetrics.Snapshot s) -> s.route() == null ? "" : s.route())
                .thenComparing(s -> s.method() == null ? "" : s.method()));
        return result;
    }

    public int dimensionCount() {
        return routes.size();
    }
}
