package com.github.lechandonga.requesttraceobservabilityjava.tests;

import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.MdcTaskDecorator;
import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步链路：traceId 跨线程池传播，且任务结束后不串号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTracePropagationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("异步链路：请求转入异步线程池后，工作线程日志/响应中的 traceId 与入站一致")
    void traceIdPropagatesIntoAsyncWorkerThread() {
        String inbound = "async-trace-0001";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", inbound);

        ResponseEntity<Map> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/demo/async-ping",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), Map.class);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(inbound, response.getHeaders().getFirst("X-Trace-Id"));
        assertEquals(inbound, response.getBody().get("traceId"), "异步工作线程拿到的标识必须一致");
        assertEquals("biz-async-",
                ((String) response.getBody().get("workerThread")).substring(0, "biz-async-".length()),
                "确认确实切换到了业务异步线程池");
    }

    @Test
    @DisplayName("异步链路：MDC 装饰器在线程复用时还原工作线程原上下文，两个并发请求互不串号")
    void mdcIsRestoredAfterTaskAndNoCrossTalk() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        TraceContext.setTraceId("trace-aaaaaaaaaaaa");
        Runnable first = decorator.decorate(() ->
                assertEquals("trace-aaaaaaaaaaaa", TraceContext.currentTraceId()));
        TraceContext.clear();

        TraceContext.setTraceId("trace-bbbbbbbbbbbb");
        Runnable second = decorator.decorate(() ->
                assertEquals("trace-bbbbbbbbbbbb", TraceContext.currentTraceId()));
        TraceContext.clear();

        AtomicReference<Throwable> workerMdcAfterTasks = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                assertNull(MDC.get("traceId"), "任务执行前工作线程不应有标识");
                first.run();
                second.run();
                assertNull(MDC.get("traceId"), "任务结束后必须还原工作线程 MDC，不能串到下一条请求");
            } catch (Throwable t) {
                workerMdcAfterTasks.set(t);
            }
        });
        worker.start();
        worker.join();

        if (workerMdcAfterTasks.get() != null) {
            throw new AssertionError(workerMdcAfterTasks.get());
        }
    }
}
