package cn.kong.eon.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 会话信息响应。
 */
public class SessionResponse {
    private String sessionId;
    private Instant createdAt;
    private Instant lastActiveAt;
    private int turnCount;

    public SessionResponse() {}

    public SessionResponse(String sessionId, Instant createdAt, Instant lastActiveAt, int turnCount) {
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.lastActiveAt = lastActiveAt;
        this.turnCount = turnCount;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
}
