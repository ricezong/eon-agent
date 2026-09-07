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
    private List<String> nudges;             // 运行时提醒：跨轮累积，每轮渲染进上下文后清空
    private String lastAssistantText;
    private boolean todoBeenUsed = false;    // 是否调用过 todo_write（激活 Todo）

    // 运行时临时字段
    private transient List<ChatMessage> currentMessages;
    private transient LlmResponse lastResponse;
    private transient List<ToolExecutionRequest> pendingToolCalls;
    private transient List<ToolExecutionResult> lastToolResults;

    public SessionState() {
        this.turnCount = 0;
        this.usageAccum = TokenUsage.zero();
        this.compressionState = new CompressionState();
        this.nudges = new ArrayList<>();
        this.todoBeenUsed = false;
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
    }

    /** 创建新的会话状态。 */
    public static SessionState create(String sessionId, String userOriginalInput) {
        SessionState s = new SessionState();
        s.sessionId = sessionId;
        s.userInput = userOriginalInput;
        return s;
    }

    /**
     * 同一会话内开始一次新的用户输入（复用本状态）。
     * 重置任务级状态（用户输入、轮数、运行时提醒与临时消息字段），
     * 保留会话级状态（token 累计、压缩状态、todo 使用标记）。
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

    /** 递增轮次计数。 */
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

    public boolean hasTodoBeenUsed() {
        return todoBeenUsed;
    }

    public void setTodoBeenUsed(boolean todoBeenUsed) {
        this.todoBeenUsed = todoBeenUsed;
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

    public List<ToolExecutionResult> getLastToolResults() {
        return lastToolResults;
    }

    public void setLastToolResults(List<ToolExecutionResult> lastToolResults) {
        this.lastToolResults = lastToolResults;
    }

}
