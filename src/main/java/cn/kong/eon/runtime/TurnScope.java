package cn.kong.eon.runtime;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.tool.model.ToolCallRecord;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;
import java.util.UUID;

/**
 * 轮次级作用域：每轮新建，轮末丢弃。
 * 装本轮 prompt、响应、待执行工具调用、工具结果。
 */
public final class TurnScope {

    private final int index;
    private final String messageId;

    private ContextBuilder prompt;
    private LlmResponse response;
    private String assistantText;
    private String thinking;
    private List<ToolExecutionRequest> pendingToolCalls = List.of();
    private List<ToolCallRecord> toolResults = List.of();

    public TurnScope(int index) {
        this.index = index;
        this.messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public int index() {
        return index;
    }

    public String messageId() {
        return messageId;
    }

    public ContextBuilder prompt() {
        return prompt;
    }

    public void setPrompt(ContextBuilder prompt) {
        this.prompt = prompt;
    }

    public LlmResponse response() {
        return response;
    }

    public void setResponse(LlmResponse response) {
        this.response = response;
    }

    public String assistantText() {
        return assistantText;
    }

    public void setAssistantText(String assistantText) {
        this.assistantText = assistantText;
    }

    public String thinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public List<ToolExecutionRequest> pendingToolCalls() {
        return pendingToolCalls;
    }

    public void setPendingToolCalls(List<ToolExecutionRequest> pendingToolCalls) {
        this.pendingToolCalls = pendingToolCalls != null ? pendingToolCalls : List.of();
    }

    public List<ToolCallRecord> toolResults() {
        return toolResults;
    }

    public void setToolResults(List<ToolCallRecord> toolResults) {
        this.toolResults = toolResults != null ? toolResults : List.of();
    }
}
