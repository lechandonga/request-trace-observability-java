package com.github.lechandonga.requesttraceobservabilityjava.observability.trace;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 线程池任务装饰器：提交任务的线程负责快照 MDC，执行任务的工作线程负责恢复并在结束后还原。
 *
 * <p>这样请求转异步处理后，工作线程里的日志仍带同一个 traceId；
 * 任务结束时恢复工作线程原有 MDC（通常为空），杜绝请求之间串号。
 */
public class MdcTaskDecorator implements org.springframework.core.task.TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> snapshot;
        try {
            snapshot = TraceContext.snapshot();
        } catch (RuntimeException e) {
            // 观测自身出问题：原样执行任务，不影响业务
            return runnable;
        }
        return () -> {
            Map<String, String> previous;
            try {
                previous = TraceContext.restore(snapshot);
            } catch (RuntimeException e) {
                runnable.run();
                return;
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
