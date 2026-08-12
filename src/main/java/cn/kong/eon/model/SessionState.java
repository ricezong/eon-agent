package cn.kong.eon.model;

import cn.kong.eon.llm.LlmResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级运行时状态。
 * 对应技术方案第 2.7 节 SessionState。
 * 贯穿整个 Agent Loop，所有组件共享此状态。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionState {
    private String sessionId;
    private String userOriginalInput;        // 用户原始请求（永不裁剪）
    private int turnCount;
    private int userTurnCount;               // 用户回合数（用于 Nudge）
    private TokenUsage usageAccum;
    private CompressionState compressionState;
    private List<String> insights;           // Insights 滚动区
    private List<String> pendingNudges;      // 临时附加层（本轮有效）
    private List<String> formatCorrections;  // 格式纠正（本轮有效）
    private boolean finished;
    private String finishReason;
    private String lastAssistantText;        // 最近一轮 assistant 文本输出

    // Profile 管理
    private boolean todoBeenUsed = false;    // LLM 是否调用过 todo_write（用于升级 Profile）

    // 运行时临时字段（不序列化）
    private transient List<ChatMessage> currentMessages;
    private transient LlmResponse lastResponse;
    private transient List<ToolExecutionRequest> pendingToolCalls;
    private transient List<ToolExecutionResult> lastToolResults;

    public SessionState() {
        this.turnCount = 0;
        this.userTurnCount = 0;
        this.usageAccum = TokenUsage.zero();
        this.compressionState = new CompressionState();
        this.insights = new ArrayList<>();
        this.pendingNudges = new ArrayList<>();
        this.formatCorrections = new ArrayList<>();
        this.finished = false;
        this.todoBeenUsed = false;
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
    }

    public static SessionState create(String sessionId, String userOriginalInput) {
        SessionState s = new SessionState();
        s.sessionId = sessionId;
        s.userOriginalInput = userOriginalInput;
        return s;
    }

    public void incrementTurn() { this.turnCount++; }
    public void decrementTurn() { if (this.turnCount > 0) this.turnCount--; }
    public void incrementUserTurn() { this.userTurnCount++; }

    public void addInsight(String insight) {
        insights.add(0, insight);  // 最新在前
    }

    public void addNudge(String nudge) {
        pendingNudges.add(nudge);
    }

    public void addFormatCorrection(String correction) {
        formatCorrections.add(correction);
    }

    public void clearTemporary() {
        pendingNudges.clear();
        formatCorrections.clear();
    }

    // --- getters / setters ---

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserOriginalInput() { return userOriginalInput; }
    public void setUserOriginalInput(String userOriginalInput) { this.userOriginalInput = userOriginalInput; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public int getUserTurnCount() { return userTurnCount; }
    public void setUserTurnCount(int userTurnCount) { this.userTurnCount = userTurnCount; }

    public TokenUsage getUsageAccum() { return usageAccum; }
    public void setUsageAccum(TokenUsage usageAccum) { this.usageAccum = usageAccum; }

    public CompressionState getCompressionState() { return compressionState; }
    public void setCompressionState(CompressionState compressionState) { this.compressionState = compressionState; }

    public List<String> getInsights() { return insights; }
    public void setInsights(List<String> insights) { this.insights = insights; }

    public List<String> getPendingNudges() { return pendingNudges; }
    public void setPendingNudges(List<String> pendingNudges) { this.pendingNudges = pendingNudges; }

    public List<String> getFormatCorrections() { return formatCorrections; }
    public void setFormatCorrections(List<String> formatCorrections) { this.formatCorrections = formatCorrections; }

    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public String getLastAssistantText() { return lastAssistantText; }
    public void setLastAssistantText(String lastAssistantText) { this.lastAssistantText = lastAssistantText; }

    public boolean hasTodoBeenUsed() { return todoBeenUsed; }
    public void setTodoBeenUsed(boolean todoBeenUsed) { this.todoBeenUsed = todoBeenUsed; }

    public List<ChatMessage> getCurrentMessages() { return currentMessages; }
    public void setCurrentMessages(List<ChatMessage> currentMessages) { this.currentMessages = currentMessages; }

    public LlmResponse getLastResponse() { return lastResponse; }
    public void setLastResponse(LlmResponse lastResponse) { this.lastResponse = lastResponse; }

    public List<ToolExecutionRequest> getPendingToolCalls() { return pendingToolCalls; }
    public void setPendingToolCalls(List<ToolExecutionRequest> pendingToolCalls) { this.pendingToolCalls = pendingToolCalls; }

    public List<ToolExecutionResult> getLastToolResults() { return lastToolResults; }
    public void setLastToolResults(List<ToolExecutionResult> lastToolResults) { this.lastToolResults = lastToolResults; }
}
