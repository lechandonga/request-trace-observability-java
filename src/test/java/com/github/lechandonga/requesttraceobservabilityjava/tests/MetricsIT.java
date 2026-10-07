package com.github.lechandonga.requesttraceobservabilityjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标查询：路由模板聚合、兜底维度、并发统计不丢数、失败量按错误码拆分。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("指标：按路由模板聚合（不同 id 不产生新标签），404 归 unmatched 兜底维度")
    void metricsAggregateByRouteTemplateAndFallbackForUnmatched() {
        restTemplate.getForEntity(url("/api/demo/agg-items/1001"), Map.class);
        restTemplate.getForEntity(url("/api/demo/agg-items/1002"), Map.class);
        restTemplate.getForEntity(url("/api/demo/agg-items/1003"), Map.class);
        restTemplate.getForEntity(url("/api/demo/agg-items/missing"), Map.class);
        restTemplate.getForEntity(url("/totally/unknown/a"), Map.class);
        restTemplate.getForEntity(url("/totally/unknown/b"), Map.class);

        Map<String, Object> view = getMetricsView();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) view.get("routes");

        Map<String, Object> itemsRoute = findRoute(routes, "GET", "/api/demo/agg-items/{id}");
        assertEquals(4L, asLong(itemsRoute.get("totalRequests")),
                "3 个不同 id 的成功/失败请求都必须聚合到同一模板维度");
        assertEquals(1L, asLong(itemsRoute.get("failedRequests")));

        @SuppressWarnings("unchecked")
        Map<String, Long> byCode = (Map<String, Long>) itemsRoute.get("failuresByCode");
        assertEquals(1L, asLong(byCode.get("NOT_FOUND")), "模板维度内保留按错误码的失败拆分");

        Map<String, Object> unmatched = findRoute(routes, "GET", "unmatched");
        assertTrue(asLong(unmatched.get("totalRequests")) >= 2L,
                "无法归类的原始地址必须走 unmatched 兜底维度");

        routes.forEach(route ->
                assertFalse(((String) route.get("route")).contains("1001")
                                || ((String) route.get("route")).contains("unknown/a"),
                        "指标标签中不得出现原始 id，标签基数必须受控"));
    }

    @Test
    @DisplayName("并发统计：高并发更新下请求量不丢数，失败量与错误码拆分正确")
    void concurrentUpdatesDoNotLoseCounts() throws InterruptedException {
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int index = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 3/4 成功，1/4 业务失败
                        if ((index * perThread + i) % 4 == 0) {
                            restTemplate.getForEntity(url("/api/demo/reject-bulk"), Map.class);
                        } else {
                            restTemplate.getForEntity(url("/api/demo/bulk-ok"), Map.class);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发请求必须在超时前全部完成");
        pool.shutdown();

        Map<String, Object> view = getMetricsView();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) view.get("routes");

        Map<String, Object> ping = findRoute(routes, "GET", "/api/demo/bulk-ok");
        Map<String, Object> reject = findRoute(routes, "GET", "/api/demo/reject-bulk");

        long total = asLong(ping.get("totalRequests")) + asLong(reject.get("totalRequests"));
        assertEquals((long) threads * perThread, total, "并发更新下计数不能丢");

        long expectedRejects = threads * perThread / 4L;
        assertEquals(expectedRejects, asLong(reject.get("failedRequests")));
        @SuppressWarnings("unchecked")
        Map<String, Long> byCode = (Map<String, Long>) reject.get("failuresByCode");
        assertEquals(expectedRejects, asLong(byCode.get("REQUEST_REJECTED")));

        Map<String, Object> latency = (Map<String, Object>) reject.get("latency");
        assertEquals(expectedRejects, asLong(latency.get("sampleCount")),
                "延迟直方图样本数必须与请求量一致");
        assertTrue(asLong(latency.get("p50Ms")) >= 0L);
        assertTrue(asLong(latency.get("p99Ms")) >= 0L);
    }

    @Test
    @DisplayName("指标：成功接口失败量为 0，延迟分布含 p50/p95/p99")
    void successRouteHasZeroFailuresAndQuantiles() {
        restTemplate.getForEntity(url("/api/demo/ping"), Map.class);
        long before = asLong(findRoute(
                (List<Map<String, Object>>) getMetricsView().get("routes"),
                "GET", "/api/demo/ping").get("totalRequests"));
        restTemplate.getForEntity(url("/api/demo/ping"), Map.class);

        Map<String, Object> ping = findRoute(
                (List<Map<String, Object>>) getMetricsView().get("routes"), "GET", "/api/demo/ping");
        assertEquals(0L, asLong(ping.get("failedRequests")));
        Map<String, Object> latency = (Map<String, Object>) ping.get("latency");
        assertTrue(asLong(latency.get("sampleCount")) >= before + 1L);
        assertTrue(asLong(latency.get("p50Ms")) >= 0L);
        assertTrue(asLong(latency.get("p95Ms")) >= 0L);
        assertTrue(asLong(latency.get("p99Ms")) >= 0L);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMetricsView() {
        ResponseEntity<Map> response =
                restTemplate.getForEntity(url("/observability/metrics"), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return response.getBody();
    }

    private Map<String, Object> findRoute(List<Map<String, Object>> routes, String method, String route) {
        return routes.stream()
                .filter(r -> method.equals(r.get("method")) && route.equals(r.get("route")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到指标维度 " + method + ' ' + route + "，实际: " + routes.stream()
                                .map(r -> r.get("method") + " " + r.get("route")).toList()));
    }
}
