package cn.kong.eon.api.dto;

/**
 * 对话响应。
 */
public class ChatResponse {
    private String sessionId;
    private String reply;
    private int turnCount;

    public ChatResponse() {}

    public ChatResponse(String sessionId, String reply, int turnCount) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.turnCount = turnCount;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
}
