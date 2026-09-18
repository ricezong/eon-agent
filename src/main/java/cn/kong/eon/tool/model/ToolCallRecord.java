package cn.kong.eon.tool.model;

/**
 * 工具执行结果。封装成功/失败状态、渲染后的内容与结构化展示内容。
 */
public record ToolCallRecord(String toolCallId, String toolName, boolean success,
                             String content, ToolResultView toolResultView) {
}
