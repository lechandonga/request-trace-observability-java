package com.github.lechandonga.requesttraceobservabilityjava.observability.error;

/**
 * 稳定的对外错误码。错误响应结构与码值集合属于接口契约，
 * 不同失败原因可凭 {@link #code()} 区分，不依赖异常类名或文案判断。
 */
public enum ErrorCode {

    INVALID_REQUEST(400, "INVALID_REQUEST", "请求参数不合法"),
    UNAUTHORIZED(401, "UNAUTHORIZED", "未认证"),
    FORBIDDEN(403, "FORBIDDEN", "禁止访问"),
    NOT_FOUND(404, "NOT_FOUND", "资源不存在"),
    METHOD_NOT_ALLOWED(405, "METHOD_NOT_ALLOWED", "请求方法不支持"),
    REQUEST_REJECTED(422, "REQUEST_REJECTED", "请求被业务拒绝"),
    TOO_MANY_REQUESTS(429, "TOO_MANY_REQUESTS", "请求过于频繁"),
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务器内部错误");

    private final int httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
