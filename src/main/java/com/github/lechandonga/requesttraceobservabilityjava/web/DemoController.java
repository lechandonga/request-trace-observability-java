package com.github.lechandonga.requesttraceobservabilityjava.web;

import com.github.lechandonga.requesttraceobservabilityjava.observability.error.BusinessException;
import com.github.lechandonga.requesttraceobservabilityjava.observability.error.ErrorCode;
import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示业务接口：覆盖正常链路、参数非法、业务拒绝、非预期异常、异步处理等场景，
 * 用于验证观测层在各条链路上的表现。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor taskExecutor;

    public DemoController(@Qualifier("requestTraceTaskExecutor") Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        log.info("handling ping");
        return Map.of("message", "pong", "traceId", TraceContext.currentTraceId());
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> getUser(@PathVariable String id) {
        log.info("handling getUser id={}", id);
        if (id.startsWith("x")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + id);
        }
        return Map.of("id", id, "name", "user-" + id, "traceId", TraceContext.currentTraceId());
    }

    @GetMapping("/greet")
    public Map<String, Object> greet(@RequestParam String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        return Map.of("greeting", "hello " + name, "traceId", TraceContext.currentTraceId());
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@Valid @RequestBody EchoRequest request) {
        return Map.of("content", request.content(), "traceId", TraceContext.currentTraceId());
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new IllegalStateException("simulated unexpected failure");
    }

    @GetMapping("/reject")
    public Map<String, Object> reject(@RequestParam(defaultValue = "演示业务拒绝") String reason) {
        throw new BusinessException(ErrorCode.REQUEST_REJECTED, reason);
    }

    @GetMapping("/reject-bulk")
    public Map<String, Object> rejectBulk() {
        throw new BusinessException(ErrorCode.REQUEST_REJECTED, "批量并发用例业务拒绝");
    }

    @GetMapping("/bulk-ok")
    public Map<String, Object> bulkOk() {
        return Map.of("message", "ok", "traceId", TraceContext.currentTraceId());
    }

    @GetMapping("/agg-items/{id}")
    public Map<String, Object> aggItems(@PathVariable String id) {
        if ("missing".equals(id)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "聚合用例：条目不存在");
        }
        return Map.of("id", id, "traceId", TraceContext.currentTraceId());
    }

    /**
     * 请求进入异步线程池继续处理：工作线程日志必须带同一个 traceId，结束后不串号。
     */
    @GetMapping("/async-ping")
    public CompletableFuture<Map<String, Object>> asyncPing() {
        String requestThread = Thread.currentThread().getName();
        log.info("submitting async work");
        return CompletableFuture.supplyAsync(() -> {
            log.info("handling async ping in worker thread");
            return Map.<String, Object>of(
                    "message", "async-pong",
                    "requestThread", requestThread,
                    "workerThread", Thread.currentThread().getName(),
                    "traceId", TraceContext.currentTraceId()
            );
        }, taskExecutor);
    }

    public record EchoRequest(@NotBlank(message = "content 不能为空") String content) {
    }
}
