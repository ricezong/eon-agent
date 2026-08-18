package cn.kong.eon.model;

/** 工具执行结果，在 SessionState 中暂存。 */
public record ToolExecutionResult(String toolCallId, String toolName, String content) {
    public static ToolExecutionResult of(String toolCallId, String toolName, String content) {
        return new ToolExecutionResult(toolCallId, toolName, content);
    }
}
