package com.github.lechandonga.requesttraceobservabilityjava.observability.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 链路关联标识的入口过滤器，在所有其他观测组件之前执行。
 *
 * <p>职责：
 * <ul>
 *   <li>解析入站 {@code X-Trace-Id}：合法沿用、非法或缺失则自动生成；</li>
 *   <li>将标识写入 MDC，使整个请求处理过程的日志自动携带；</li>
 *   <li>把标识写入响应头，调用方可直接取回；</li>
 *   <li>异步场景（ASYNC dispatch）下从 request attribute 恢复 MDC，
 *       dispatch 结束后清理，避免线程复用串号。</li>
 * </ul>
 *
 * <p>本过滤器只做观测动作，不吞业务异常；观测自身异常降级放行。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private static final String SNAPSHOT_ATTR = TraceFilter.class.getName() + ".SNAPSHOT";

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean asyncDispatch = "ASYNC".equals(request.getDispatcherType().name());
        boolean installed = false;
        String traceId = null;

        try {
            if (asyncDispatch) {
                Object snapshot = request.getAttribute(SNAPSHOT_ATTR);
                if (snapshot instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> mdc = (Map<String, String>) raw;
                    MDC.setContextMap(mdc);
                    traceId = mdc.get(TraceContext.MDC_TRACE_ID);
                    installed = true;
                }
            } else {
                traceId = TraceIdGenerator.resolve(request.getHeader(TraceContext.TRACE_HEADER));
                TraceContext.setTraceId(traceId);
                request.setAttribute(SNAPSHOT_ATTR, TraceContext.snapshot());
                installed = true;
            }
            if (traceId != null) {
                response.setHeader(TraceContext.TRACE_HEADER, traceId);
            }
        } catch (RuntimeException observabilityFailure) {
            // 观测自身出问题：记录后继续执行链路，绝不阻断业务
            log.error("trace filter observability failure, request proceeds", observabilityFailure);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            if (installed && (asyncDispatch || !request.isAsyncStarted())) {
                MDC.clear();
            }
        }
    }
}
