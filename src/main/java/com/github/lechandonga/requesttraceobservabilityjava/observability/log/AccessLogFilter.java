package com.github.lechandonga.requesttraceobservabilityjava.observability.log;

import com.github.lechandonga.requesttraceobservabilityjava.observability.error.ErrorCode;
import com.github.lechandonga.requesttraceobservabilityjava.observability.error.ErrorResponse;
import com.github.lechandonga.requesttraceobservabilityjava.observability.error.FailureMarker;
import com.github.lechandonga.requesttraceobservabilityjava.observability.error.GlobalExceptionHandler;
import com.github.lechandonga.requesttraceobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.lechandonga.requesttraceobservabilityjava.observability.metrics.RouteKeyResolver;
import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 访问日志与指标的统一收口过滤器，在过滤器链最内层执行（路由模板已解析）。
 *
 * <p>保证：
 * <ul>
 *   <li>每个请求（含异步 ASYNC dispatch）恰好输出一条结构化访问日志、记录一次指标；</li>
 *   <li>失败原因来自 {@link GlobalExceptionHandler} 写入的 {@link FailureMarker}，
 *       同一失败请求不会重复刷日志；</li>
 *   <li>漏网异常在此兜底为稳定的 {@link ErrorResponse}，同样只记一次；</li>
 *   <li>所有观测动作 try/catch 降级，绝不把观测组件异常透给调用方。</li>
 * </ul>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS_LOG");

    private static final String COMPLETED_ATTR = AccessLogFilter.class.getName() + ".COMPLETED";
    private static final String START_NANOS_ATTR = AccessLogFilter.class.getName() + ".START_NANOS";

    private final MetricsRegistry metricsRegistry;
    private final ObjectMapper objectMapper;

    public AccessLogFilter(MetricsRegistry metricsRegistry, ObjectMapper objectMapper) {
        this.metricsRegistry = metricsRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getAttribute(START_NANOS_ATTR) == null) {
            request.setAttribute(START_NANOS_ATTR, System.nanoTime());
        }
        try {
            chain.doFilter(request, response);
        } catch (Throwable escaped) {
            // 理论上 @RestControllerAdvice 已兜住，这里是最后一道防线：写稳定错误结构，且只记一次
            handleEscapedFailure(request, response, escaped);
        }

        if (request.isAsyncStarted()) {
            // 异步处理中，本次 dispatch 不记账；ASYNC dispatch 回来后由本过滤器完成收口
            return;
        }
        completeIfFirstTime(request, response);
    }

    private void handleEscapedFailure(HttpServletRequest request, HttpServletResponse response,
                                      Throwable escaped) throws ServletException, IOException {
        FailureMarker marker = new FailureMarker(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(), escaped.getClass().getName());
        request.setAttribute(GlobalExceptionHandler.FAILURE_ATTR, marker);
        accessLog.error("unhandled exception escaped filter chain", escaped);

        if (response.isCommitted()) {
            if (escaped instanceof ServletException se) {
                throw se;
            }
            if (escaped instanceof IOException ioe) {
                throw ioe;
            }
            throw new ServletException(escaped);
        }
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(), request.getRequestURI(),
                TraceContext.currentTraceId());
        response.setStatus(ErrorCode.INTERNAL_ERROR.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        try {
            objectMapper.writeValue(response.getWriter(), body);
        } catch (IOException writeFailure) {
            accessLog.error("failed to write fallback error response", writeFailure);
            throw escaped instanceof ServletException se ? se : new ServletException(escaped);
        }
    }

    private void completeIfFirstTime(HttpServletRequest request, HttpServletResponse response) {
        AtomicBoolean completed = (AtomicBoolean) request.getAttribute(COMPLETED_ATTR);
        if (completed == null) {
            completed = new AtomicBoolean(false);
            request.setAttribute(COMPLETED_ATTR, completed);
        }
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        long startNanos = (long) request.getAttribute(START_NANOS_ATTR);
        long latencyMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        int status = response.getStatus();
        FailureMarker marker = (FailureMarker) request.getAttribute(GlobalExceptionHandler.FAILURE_ATTR);

        boolean failed = marker != null || status >= HttpStatus.BAD_REQUEST.value();
        String errorCode = marker != null ? marker.errorCode().code() : resolveErrorCodeByStatus(status);
        String route;
        try {
            route = RouteKeyResolver.resolve(request);
        } catch (RuntimeException e) {
            route = RouteKeyResolver.UNKNOWN_ROUTE;
        }

        try {
            metricsRegistry.record(request.getMethod(), route, latencyMs, failed, failed ? errorCode : null);
        } catch (RuntimeException e) {
            accessLog.error("metrics recording observability failure, request proceeds", e);
        }

        try {
            writeAccessLog(request, status, latencyMs, route, failed, errorCode, marker);
        } catch (RuntimeException e) {
            accessLog.error("access log observability failure, request proceeds", e);
        }
    }

    private String resolveErrorCodeByStatus(int status) {
        if (status >= 500) {
            return ErrorCode.INTERNAL_ERROR.code();
        }
        if (status == 404) {
            return ErrorCode.NOT_FOUND.code();
        }
        if (status == 405) {
            return ErrorCode.METHOD_NOT_ALLOWED.code();
        }
        if (status >= 400) {
            return ErrorCode.INVALID_REQUEST.code();
        }
        return null;
    }

    private void writeAccessLog(HttpServletRequest request, int status, long latencyMs, String route,
                                boolean failed, String errorCode, FailureMarker marker) {
        String outcome = failed ? "FAILURE" : "SUCCESS";
        String reason = marker == null ? "" : (marker.reason() == null ? "" : marker.reason());
        String exceptionClass = marker == null ? "" : (marker.exceptionClass() == null ? "" : marker.exceptionClass());

        if (failed) {
            accessLog.warn(
                    "access {} method={} route={} path={} status={} errorCode={} reason=\"{}\" exception={} latencyMs={} traceId={}",
                    outcome, request.getMethod(), route, request.getRequestURI(), status,
                    errorCode, reason, exceptionClass, latencyMs, TraceContext.currentTraceId());
        } else {
            accessLog.info(
                    "access {} method={} route={} path={} status={} latencyMs={} traceId={}",
                    outcome, request.getMethod(), route, request.getRequestURI(), status,
                    latencyMs, TraceContext.currentTraceId());
        }
    }
}
