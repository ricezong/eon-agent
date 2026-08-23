package cn.kong.eon.api.exception;

/**
 * 会话忙碌异常。同一会话已有正在执行的 Agent 任务时抛出。
 */
public class SessionBusyException extends RuntimeException {
    private final String sessionId;

    public SessionBusyException(String sessionId) {
        super("会话 " + sessionId + " 正在执行中，请稍后再试");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
