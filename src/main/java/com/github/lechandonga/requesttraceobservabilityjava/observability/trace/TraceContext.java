package com.github.lechandonga.requesttraceobservabilityjava.observability.trace;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 请求链路关联标识的上下文持有器。
 *
 * <p>标识存放在 SLF4J 的 {@link MDC} 中，业务代码打日志时会自动带上；
 * 同时提供 {@link #snapshot()} / {@link #restore(Map)} 供线程切换（异步线程池）时传播。
 */
public final class TraceContext {

    /** 关联标识的请求头名称（入站沿用、出站回写）。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** MDC 中的 key，对应 logback pattern 里的 %X{traceId}。 */
    public static final String MDC_TRACE_ID = "traceId";

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    public static void setTraceId(String traceId) {
        MDC.put(MDC_TRACE_ID, traceId);
    }
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
    }

    /**
     * 快照当前 MDC（包含 traceId 及任何已有上下文），用于提交异步任务前在调用线程捕获。
     */
    public static Map<String, String> snapshot() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 在目标线程上恢复 MDC，返回恢复前的旧上下文，便于任务结束后原样还原，避免串号。
     */
    public static Map<String, String> restore(Map<String, String> snapshot) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
        return previous;
    }
}
