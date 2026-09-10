package cn.kong.eon.agent.exec;

import cn.kong.eon.agent.event.StructuredContent;
import cn.kong.eon.tool.ToolOutcome;

/**
 * 工具执行结果。封装成功/失败状态、渲染后的内容与结构化展示内容。
 */
public record ToolExecResult(String toolCallId, String toolName, boolean success,
                             String content, StructuredContent structuredContent) {

    /** 从 ToolOutcome 构建结果。 */
    public static ToolExecResult of(String toolCallId, String toolName, ToolOutcome outcome,
                                    String renderedContent, StructuredContent structuredContent) {
        return new ToolExecResult(toolCallId, toolName, outcome.success(), renderedContent, structuredContent);
    }
}
