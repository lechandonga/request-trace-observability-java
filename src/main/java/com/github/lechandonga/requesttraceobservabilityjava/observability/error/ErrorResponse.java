package com.github.lechandonga.requesttraceobservabilityjava.observability.error;

import java.time.Instant;

/**
 * 统一且稳定的对外错误结构：所有失败响应均返回该 JSON 结构。
 *
 * @param timestamp 失败发生时间（UTC）
 * @param status    HTTP 状态码
 * @param code      稳定错误码，调用方按此区分失败原因
 * @param message   面向调用方的错误描述（不含堆栈与内部细节）
 * @param path      失败请求路径
 * @param traceId   关联标识，与访问日志、响应头中的标识一致
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId
) {
    public static ErrorResponse of(ErrorCode errorCode, String message, String path, String traceId) {
        return new ErrorResponse(Instant.now(), errorCode.httpStatus(), errorCode.code(),
                message == null ? errorCode.defaultMessage() : message, path, traceId);
    }
}
