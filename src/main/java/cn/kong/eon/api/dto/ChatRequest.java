package cn.kong.eon.api.dto;

/**
 * 同步对话请求。
 */
public class ChatRequest {
    private String sessionId;
    private String message;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
