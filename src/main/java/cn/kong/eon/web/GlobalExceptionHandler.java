package cn.kong.eon.web;

import cn.kong.eon.web.exception.SessionBusyException;
import cn.kong.eon.web.exception.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。统一异常响应格式，避免每个接口手动 try-catch。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e, WebRequest request) {
        log.warn("参数错误: {}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSessionNotFound(SessionNotFoundException e, WebRequest request) {
        log.warn("会话不存在: {}", e.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "session_not_found", e.getMessage());
    }

    @ExceptionHandler(SessionBusyException.class)
    public ResponseEntity<Map<String, Object>> handleSessionBusy(SessionBusyException e, WebRequest request) {
        log.warn("会话忙: {}", e.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "session_busy", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e, WebRequest request) {
        log.warn("状态错误: {}", e.getMessage());
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "state_error", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e, WebRequest request) {
        log.error("运行时异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "runtime_error", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception e, WebRequest request) {
        log.error("未预期异常: {}", e.getMessage(), e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String type, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("type", type);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
