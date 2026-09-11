package cn.kong.eon.web.exception;

/**
 * 同一会话已有任务在执行。HTTP 语义为 409 Conflict。
 */
public class SessionBusyException extends RuntimeException {

    private final String sessionId;

    public SessionBusyException(String sessionId) {
        super("会话忙: " + sessionId);
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
