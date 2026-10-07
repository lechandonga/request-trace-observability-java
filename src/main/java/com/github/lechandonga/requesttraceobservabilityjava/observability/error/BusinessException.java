package com.github.lechandonga.requesttraceobservabilityjava.observability.error;

/**
 * 可预期的业务失败：由业务代码主动抛出，携带稳定错误码与面向调用方的提示信息。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
