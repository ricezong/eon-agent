package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Checkpoint 快照，崩溃后从最新 checkpoint 恢复。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Checkpoint {
    private String checkpointId;     // 快照 ID
    private String sessionId;        // 会话 ID
    private int turnCount;           // 轮次
    private java.util.List<TodoItem> todoSnapshot;  // Todo 快照
    private TokenUsage usageAccum;   // 累计 token
    private CompressionState compressionState;      // 压缩状态
    private java.util.List<String> insightsSnapshot; // Insights 快照
    private Instant createdAt;       // 创建时间

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
