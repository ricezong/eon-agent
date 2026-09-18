package cn.kong.eon.web.exception;

import org.springframework.http.HttpStatus;

/**
 * 对外接口异常。HTTP 状态码与错误类型码在抛出点确定，
 * {@code GlobalExceptionHandler} 只做一次统一转换，前端按 type 分诊。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    private ApiException(HttpStatus status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    /** 参数或路径不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    /** 会话不存在（索引中查不到，或会话目录缺失）。 */
    public static ApiException sessionNotFound(String sessionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "session_not_found", "会话不存在: " + sessionId);
    }

    /** 同会话已有任务在执行。 */
    public static ApiException sessionBusy(String sessionId) {
        return new ApiException(HttpStatus.CONFLICT, "session_busy", "会话忙: " + sessionId);
    }

    /** 会话内文件不存在、不是普通文件，或会话目录缺失。 */
    public static ApiException fileNotFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "file_not_found", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }
}
