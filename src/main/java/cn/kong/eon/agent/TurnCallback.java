package cn.kong.eon.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Agent 执行过程中的回调接口。
 * <p>
 * 在 {@link EonAgent#runStream} 的关键节点被调用，用于 SSE 流式推送事件。
 * 所有回调方法不应抛出异常；如果内部出错，静默处理即可，不应中断 Agent 主循环。
 */
public interface TurnCallback {

    /**
     * Agent 开始运行。
     */
    default void onRunStart(String sessionId, String userInput) {
    }

    /**
     * 每个 Turn 开始。
     */
    default void onTurnStart(int turnNumber) {
    }

    /**
     * LLM 响应到达，包含思考文本和工具调用请求。
     */
    default void onLlmResponse(String thought, List<String> toolNames) {
    }

    /**
     * 工具开始执行。
     */
    default void onToolStart(String toolName, String toolCallId) {
    }

    /**
     * 单个工具执行完成。
     */
    default void onToolResult(String toolName, boolean success, String summary) {
    }

    /**
     * 每个 Turn 结束。
     */
    default void onTurnEnd(int turnCount, int totalTokens) {
    }

    /**
     * Agent 正常输出最终结果。
     */
    default void onOutput(String output, int turnCount, int totalTokens) {
    }

    /**
     * Agent 被强制终止。
     */
    default void onTerminate(String reason, int turnCount, int totalTokens) {
    }

    /**
     * Agent 执行出错。
     */
    default void onError(String error) {
    }
}
