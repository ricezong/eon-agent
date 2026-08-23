package cn.kong.eon.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 交互状态响应。返回待处理的交互问题信息。
 */
public class InteractionResponse {
    private String sessionId;
    private boolean pending;
    private String title;
    private List<Map<String, Object>> questions;
    private Instant createdAt;

    public InteractionResponse() {}

    public static InteractionResponse pending(String sessionId, String title,
                                               List<Map<String, Object>> questions, Instant createdAt) {
        InteractionResponse r = new InteractionResponse();
        r.sessionId = sessionId;
        r.pending = true;
        r.title = title;
        r.questions = questions;
        r.createdAt = createdAt;
        return r;
    }

    public static InteractionResponse idle(String sessionId) {
        InteractionResponse r = new InteractionResponse();
        r.sessionId = sessionId;
        r.pending = false;
        return r;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<Map<String, Object>> getQuestions() { return questions; }
    public void setQuestions(List<Map<String, Object>> questions) { this.questions = questions; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
