package cn.kong.eon.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 同步对话请求。
 */
public class ChatRequest {
    private String sessionId;

    @Size(max = 32000, message = "message 长度不能超过 32000 字符")
    private String message;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
