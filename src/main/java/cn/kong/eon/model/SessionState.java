package cn.kong.eon.model;

import cn.kong.eon.llm.LlmResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级运行时状态，贯穿整个 Agent Loop，所有组件共享。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionState {
    private String sessionId;
    private String userInput;
    private int turnCount;
    private TokenUsage usageAccum;
    private CompressionState compressionState;
    private List<String> nudges;             // 跨轮累积的运行时提醒，渲染进上下文后清空
    private String lastAssistantText;
    // 运行时临时字段（不持久化）
    private transient List<ChatMessage> currentMessages;
    private transient LlmResponse lastResponse;
    private transient List<ToolExecutionRequest> pendingToolCalls;
    private transient List<ToolExecResult> lastToolResults;

    public SessionState() {
        this.turnCount = 0;
        this.usageAccum = TokenUsage.zero();
        this.compressionState = new CompressionState();
        this.nudges = new ArrayList<>();
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
    }

    /** 创建新会话状态。 */
    public static SessionState create(String sessionId, String userOriginalInput) {
        SessionState s = new SessionState();
        s.sessionId = sessionId;
        s.userInput = userOriginalInput;
        return s;
    }

    /**
     * 同一会话内开始新的用户输入。重置任务级状态，保留会话级状态（token 累计、压缩状态、todo 标记）。
     */
    public void beginRun(String userInput) {
        this.userInput = userInput;
        this.turnCount = 0;
        this.nudges.clear();
        this.lastAssistantText = null;
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
        this.currentMessages = null;
        this.lastResponse = null;
    }

    /** 递增轮次。 */
    public void incrementTurn() {
        this.turnCount++;
    }

    /** 添加运行时提醒。 */
    public void addNudge(String nudge) {
        nudges.add(nudge);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }

    public TokenUsage getUsageAccum() {
        return usageAccum;
    }

    public void setUsageAccum(TokenUsage usageAccum) {
        this.usageAccum = usageAccum;
    }

    public CompressionState getCompressionState() {
        return compressionState;
    }


    public List<String> getNudges() {
        return nudges;
    }

    public String getLastAssistantText() {
        return lastAssistantText;
    }

    public void setLastAssistantText(String lastAssistantText) {
        this.lastAssistantText = lastAssistantText;
    }

    public List<ChatMessage> getCurrentMessages() {
        return currentMessages;
    }

    public void setCurrentMessages(List<ChatMessage> currentMessages) {
        this.currentMessages = currentMessages;
    }

    public LlmResponse getLastResponse() {
        return lastResponse;
    }

    public void setLastResponse(LlmResponse lastResponse) {
        this.lastResponse = lastResponse;
    }

    public List<ToolExecutionRequest> getPendingToolCalls() {
        return pendingToolCalls;
    }

    public void setPendingToolCalls(List<ToolExecutionRequest> pendingToolCalls) {
        this.pendingToolCalls = pendingToolCalls;
    }

    public List<ToolExecResult> getLastToolResults() {
        return lastToolResults;
    }

    public void setLastToolResults(List<ToolExecResult> lastToolResults) {
        this.lastToolResults = lastToolResults;
    }

}
