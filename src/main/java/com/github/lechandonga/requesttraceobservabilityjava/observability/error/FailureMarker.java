package com.github.lechandonga.requesttraceobservabilityjava.observability.error;

/**
 * 请求失败信息载体，由异常处理器写入 request attribute，供访问日志过滤器统一记录，
 * 保证“同一个失败请求只记一次、原因不丢”。
 */
public record FailureMarker(ErrorCode errorCode, String reason, String exceptionClass) {
}
