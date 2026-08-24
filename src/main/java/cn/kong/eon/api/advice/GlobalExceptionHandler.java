package cn.kong.eon.api.advice;

import cn.kong.eon.api.dto.ErrorResponse;
import cn.kong.eon.api.exception.SessionBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 * <p>
 * 捕获 Controller 层抛出的异常，统一返回 {@link ErrorResponse}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 会话忙碌 → 409 */
    @ExceptionHandler(SessionBusyException.class)
    public ResponseEntity<ErrorResponse> handleSessionBusy(SessionBusyException e) {
        log.warn("Session busy: {}", e.getSessionId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(409, "SESSION_BUSY", e.getMessage()));
    }

    /** 非法参数 → 400，不泄露内部异常详情 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "Bad Request", "请求参数错误"));
    }

    /** 非法状态 → 409，不泄露内部异常详情 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("Conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(409, "Conflict", "请求处理冲突，请重试"));
    }

    /** 运行时异常 → 500，不泄露内部异常详情 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        log.error("Internal error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error",
                        "服务器内部错误，请联系管理员"));
    }

    /** 兜底 → 500，不泄露内部异常详情 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error",
                        "服务器内部错误，请联系管理员"));
    }
}
