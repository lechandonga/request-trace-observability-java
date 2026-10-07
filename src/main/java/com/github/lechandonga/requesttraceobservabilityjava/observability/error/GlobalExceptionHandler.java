package com.github.lechandonga.requesttraceobservabilityjava.observability.error;

import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 统一异常处理：把各类失败映射为稳定的 {@link ErrorResponse}，
 * 并把失败原因标记到 request attribute，交由访问日志过滤器记录一次。
 *
 * <p>5xx 在此记录完整堆栈（属于服务端真实故障），4xx 不打堆栈；
 * 处理器自身若再出问题，兜底返回 INTERNAL_ERROR，绝不把观测组件异常透给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public static final String FAILURE_ATTR = GlobalExceptionHandler.class.getName() + ".FAILURE";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return markAndBuild(ex.errorCode(), ex.getMessage(), ex, request, false);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalid(Exception ex, HttpServletRequest request) {
        String reason = switch (ex) {
            case MethodArgumentNotValidException valid -> valid.getBindingResult().getFieldErrors().stream()
                    .map(this::fieldErrorMessage)
                    .collect(Collectors.joining("; "));
            case MissingServletRequestParameterException missing ->
                    "缺少必填参数: " + missing.getParameterName();
            case MethodArgumentTypeMismatchException mismatch ->
                    "参数类型不正确: " + mismatch.getName();
            default -> ex.getMessage();
        };
        return markAndBuild(ErrorCode.INVALID_REQUEST, reason, ex, request, false);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return markAndBuild(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), ex, request, false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        return markAndBuild(ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.defaultMessage() + ": " + ex.getMethod(), ex, request, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return markAndBuild(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                ex, request, true);
    }

    private String fieldErrorMessage(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> markAndBuild(ErrorCode errorCode, String message, Exception ex,
                                                       HttpServletRequest request, boolean serverFault) {
        try {
            if (serverFault) {
                log.error("request failed with server error [{}]", errorCode.code(), ex);
            } else {
                log.warn("request rejected [{}]: {}", errorCode.code(), message);
            }
            request.setAttribute(FAILURE_ATTR,
                    new FailureMarker(errorCode, message, ex.getClass().getName()));
        } catch (Throwable observabilityFailure) {
            // 观测动作（记日志/打标记）失败：只记录，不影响错误响应本身
            log.error("failure marker observability error, response proceeds", observabilityFailure);
        }
        ErrorResponse body;
        try {
            body = ErrorResponse.of(errorCode, message, request.getRequestURI(), TraceContext.currentTraceId());
        } catch (Throwable observabilityFailure) {
            log.error("error response build observability error, fallback response used", observabilityFailure);
            body = new ErrorResponse(java.time.Instant.now(), errorCode.httpStatus(), errorCode.code(),
                    errorCode.defaultMessage(), request.getRequestURI(), null);
        }
        return ResponseEntity.status(HttpStatus.valueOf(errorCode.httpStatus())).body(body);
    }
}
