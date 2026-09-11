package cn.kong.eon.web.exception;

/**
 * 会话不存在（索引中查不到）。HTTP 语义为 404 Not Found。
 */
public class SessionNotFoundException extends RuntimeException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("会话不存在: " + sessionId);
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
