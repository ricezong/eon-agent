package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Checkpoint 快照。
 * 对应技术方案第 2.4 节。
 * 每次 todo_write 或每 5 轮落盘一次，崩溃后从最新 checkpoint 恢复。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Checkpoint {
    private String checkpointId;
    private String sessionId;
    private int turnCount;
    private java.util.List<TodoItem> todoSnapshot;
    private TokenUsage usageAccum;
    private CompressionState compressionState;
    private java.util.List<String> insightsSnapshot;
    private Instant createdAt;

    public Checkpoint() {}

    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public java.util.List<TodoItem> getTodoSnapshot() { return todoSnapshot; }
    public void setTodoSnapshot(java.util.List<TodoItem> todoSnapshot) { this.todoSnapshot = todoSnapshot; }

    public TokenUsage getUsageAccum() { return usageAccum; }
    public void setUsageAccum(TokenUsage usageAccum) { this.usageAccum = usageAccum; }

    public CompressionState getCompressionState() { return compressionState; }
    public void setCompressionState(CompressionState compressionState) { this.compressionState = compressionState; }

    public java.util.List<String> getInsightsSnapshot() { return insightsSnapshot; }
    public void setInsightsSnapshot(java.util.List<String> insightsSnapshot) { this.insightsSnapshot = insightsSnapshot; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
