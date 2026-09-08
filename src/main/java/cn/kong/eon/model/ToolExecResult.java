package cn.kong.eon.model;

import cn.kong.eon.tool.ToolOutcome;

/**
 * 工具执行结果。封装成功/失败状态与渲染后的内容。
 */
public record ToolExecResult(String toolCallId, String toolName, boolean success, String content) {

    /** 从 ToolOutcome 构建结果。 */
    public static ToolExecResult of(String toolCallId, String toolName, ToolOutcome outcome, String renderedContent) {
        return new ToolExecResult(toolCallId, toolName, outcome.success(), renderedContent);
    }
}
