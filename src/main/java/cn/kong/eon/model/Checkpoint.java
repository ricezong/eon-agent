package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Checkpoint 快照，崩溃后从最新 checkpoint 恢复。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Checkpoint {
    private String checkpointId;
    private String sessionId;
    private int turnCount;
    private java.util.List<TodoItem> todoSnapshot;
    private TokenUsage usageAccum;
    private CompressionState compressionState;
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

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
