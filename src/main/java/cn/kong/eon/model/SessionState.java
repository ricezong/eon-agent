package cn.kong.eon.model;

import cn.kong.eon.agent.hook.StopReason;
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
    private List<String> pendingNudges;      // 运行时提醒（本轮有效）
    private List<String> formatCorrections;  // 格式纠正（本轮有效）
    private String lastAssistantText;

    private boolean todoBeenUsed = false;    // 是否调用过 todo_write（激活 TodoNavigator）
    private boolean budgetSoftTriggered = false; // 预算软阈值是否已触发（会话级状态）

    private transient StopState stopState;   // 优雅停止状态

    // 运行时临时字段
    private transient List<ChatMessage> currentMessages;
    private transient LlmResponse lastResponse;
    private transient List<ToolExecutionRequest> pendingToolCalls;
    private transient List<ToolExecutionResult> lastToolResults;

    public SessionState() {
        this.turnCount = 0;
        this.usageAccum = TokenUsage.zero();
        this.compressionState = new CompressionState();
        this.pendingNudges = new ArrayList<>();
        this.formatCorrections = new ArrayList<>();
        this.todoBeenUsed = false;
        this.budgetSoftTriggered = false;
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
    }

    public static SessionState create(String sessionId, String userOriginalInput) {
        SessionState s = new SessionState();
        s.sessionId = sessionId;
        s.userInput = userOriginalInput;
        return s;
    }

    /**
     * 同一会话内开始一次新的用户输入（复用本状态）。
     * <p>
     * 重置任务级状态：用户输入、轮数、运行时提醒与临时消息字段；
     * 保留会话级状态：token 累计、压缩状态、todo 使用标记、预算软触发标记。
     * turnCount 归零使步数上限按单次任务计；
     * lastTurnCompressed 随之归零，保持轮数触发判断的基准一致。
     */
    public void beginRun(String userInput) {
        this.userInput = userInput;
        this.turnCount = 0;
        this.compressionState.setLastTurnCompressed(0);
        this.pendingNudges.clear();
        this.formatCorrections.clear();
        this.lastAssistantText = null;
        this.pendingToolCalls = new ArrayList<>();
        this.lastToolResults = new ArrayList<>();
        this.currentMessages = null;
        this.lastResponse = null;
    }

    public void incrementTurn() {
        this.turnCount++;
    }

    public void addNudge(String nudge) {
        pendingNudges.add(nudge);
    }

    public void addFormatCorrection(String correction) {
        formatCorrections.add(correction);
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

    public void setCompressionState(CompressionState compressionState) {
        this.compressionState = compressionState;
    }

    public List<String> getPendingNudges() {
        return pendingNudges;
    }

    public void setPendingNudges(List<String> pendingNudges) {
        this.pendingNudges = pendingNudges;
    }

    public List<String> getFormatCorrections() {
        return formatCorrections;
    }

    public void setFormatCorrections(List<String> formatCorrections) {
        this.formatCorrections = formatCorrections;
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

    public boolean isBudgetSoftTriggered() {
        return budgetSoftTriggered;
    }

    public void setBudgetSoftTriggered(boolean budgetSoftTriggered) {
        this.budgetSoftTriggered = budgetSoftTriggered;
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

    public StopState getStopState() {
        return stopState;
    }

    public void setStopState(StopState stopState) {
        this.stopState = stopState;
    }

    public boolean isStopRequested() {
        return stopState != null && stopState.isActive();
    }

    /**
     * 优雅停止状态：NONE → REQUESTED → GRACE_PERIOD → FORCED。
     */
    public static class StopState {
        private StopReason reason;
        private int remainingGraceSteps;

        public static StopState none() {
            return new StopState();
        }

        public void request(StopReason reason) {
            this.reason = reason;
            this.remainingGraceSteps = reason.getGraceSteps();
        }

        /**
         * 消耗一个 grace step，返回是否还有剩余。
         */
        public boolean consumeGraceStep() {
            if (remainingGraceSteps > 0) {
                remainingGraceSteps--;
            }
            return remainingGraceSteps > 0;
        }

        public boolean isActive() {
            return reason != null;
        }

        public StopReason getReason() {
            return reason;
        }

        public int getRemainingGraceSteps() {
            return remainingGraceSteps;
        }
    }
}
