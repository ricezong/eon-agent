package cn.kong.eon.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * SSE 事件模型。对应 Agent 执行过程中的各种阶段事件。
 */
public class AgentEvent {
    private EventType type;
    private String sessionId;
    private Instant timestamp;
    private Integer turn;
    private String thought;
    private List<String> toolNames;
    private String toolName;
    private Boolean success;
    private String summary;
    private String output;
    private String error;
    private Integer totalTokens;

    public AgentEvent() {
        this.timestamp = Instant.now();
    }

    public static AgentEvent runStart(String sessionId) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.RUN_START;
        e.sessionId = sessionId;
        return e;
    }

    public static AgentEvent turnStart(int turn) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TURN_START;
        e.turn = turn;
        return e;
    }

    public static AgentEvent llmResponse(int turn, String thought, List<String> toolNames) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.LLM_RESPONSE;
        e.turn = turn;
        e.thought = thought;
        e.toolNames = toolNames;
        return e;
    }

    public static AgentEvent toolStart(int turn, String toolName, String toolCallId) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TOOL_START;
        e.turn = turn;
        e.toolName = toolName;
        return e;
    }

    public static AgentEvent toolResult(int turn, String toolName, boolean success, String summary) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TOOL_RESULT;
        e.turn = turn;
        e.toolName = toolName;
        e.success = success;
        e.summary = summary;
        return e;
    }

    public static AgentEvent turnEnd(int turn, int totalTokens) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TURN_END;
        e.turn = turn;
        e.totalTokens = totalTokens;
        return e;
    }

    public static AgentEvent done(String sessionId, String output, int turnCount, int totalTokens) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.DONE;
        e.sessionId = sessionId;
        e.output = output;
        e.turn = turnCount;
        e.totalTokens = totalTokens;
        return e;
    }

    public static AgentEvent terminated(String sessionId, String reason, int turnCount, int totalTokens) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.TERMINATED;
        e.sessionId = sessionId;
        e.output = reason;
        e.turn = turnCount;
        e.totalTokens = totalTokens;
        return e;
    }

    public static AgentEvent error(String sessionId, String errorMessage) {
        AgentEvent e = new AgentEvent();
        e.type = EventType.ERROR;
        e.sessionId = sessionId;
        e.error = errorMessage;
        return e;
    }

    // --- getters ---

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Integer getTurn() { return turn; }
    public void setTurn(Integer turn) { this.turn = turn; }

    public String getThought() { return thought; }
    public void setThought(String thought) { this.thought = thought; }

    public List<String> getToolNames() { return toolNames; }
    public void setToolNames(List<String> toolNames) { this.toolNames = toolNames; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    /** SSE 事件类型。 */
    public enum EventType {
        RUN_START,       // Agent 开始运行
        TURN_START,      // Turn 开始
        LLM_RESPONSE,    // LLM 响应到达（思考文本 + 工具调用）
        TOOL_START,      // 工具开始执行
        TOOL_RESULT,     // 工具执行完成
        TURN_END,        // Turn 结束
        DONE,            // 正常完成
        TERMINATED,      // 被强制终止
        ERROR            // 出错
    }
}
