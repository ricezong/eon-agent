package cn.kong.eon.session;

import cn.kong.eon.agent.exec.ToolExecResult;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.agent.context.CompressionState;
import cn.kong.eon.llm.TokenUsage;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    private List<String> nudges;
    private String lastAssistantText;
    // 运行时临时字段（不持久化）
    private transient List<ChatMessage> currentMessages;
    private transient LlmResponse lastResponse;
    private transient List<ToolExecutionRequest> pendingToolCalls;
    private transient List<ToolExecResult> lastToolResults;
    /** SSE 事件链路的 turnId，每次用户消息开始时生成 */
    private transient volatile String turnId;
    /** 当前轮的 messageId（LLM 回复标识） */
    private transient volatile String messageId;
    /** 用户中断标志，由 HTTP 层设置 */
    private transient volatile boolean interrupted;

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
        this.turnId = "turn_" + UUID.randomUUID().toString().substring(0, 8);
        this.messageId = null;
        this.interrupted = false;
    }

    /** 递增轮次，生成新的 messageId。 */
    public void incrementTurn() {
        this.turnCount++;
        this.messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 添加运行时提醒。 */
    public void addNudge(String nudge) {
        nudges.add(nudge);
    }

    /** 请求中断当前任务。 */
    public void requestInterrupt() {
        this.interrupted = true;
    }

    /** 是否已请求中断。 */
    public boolean isInterrupted() {
        return interrupted;
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

    public String getTurnId() {
        return turnId;
    }

    public String getMessageId() {
        return messageId;
    }
}
