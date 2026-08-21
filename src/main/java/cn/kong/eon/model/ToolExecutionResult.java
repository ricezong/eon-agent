package cn.kong.eon.model;

import cn.kong.eon.tool.ToolOutcome;

/**
 * 工具执行结果，在 SessionState 中暂存。
 * 封装成功/失败状态与渲染后的内容，消费方通过 {@link #success()} 判定状态。
 */
public record ToolExecutionResult(String toolCallId, String toolName, boolean success, String content) {

    public static ToolExecutionResult of(String toolCallId, String toolName, ToolOutcome outcome, String renderedContent) {
        return new ToolExecutionResult(toolCallId, toolName, outcome.success(), renderedContent);
    }
}
