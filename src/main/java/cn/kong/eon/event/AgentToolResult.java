package cn.kong.eon.event;

import cn.kong.eon.tool.model.ToolResultView;

import java.time.Instant;

/**
 * 工具执行结果事件。
 * content 是给模型的内容；structuredContent 用于界面展示，可含 text/file/image/artifact。
 */
public record AgentToolResult(
        String turnId,
        String toolUseId,
        String name,
        String content,
        ToolResultView toolResultView,
        boolean success,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "agent.tool_result";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitToolResult(this);
    }

    public static AgentToolResult now(String turnId, String toolUseId, String name,
                                      String content, ToolResultView toolResultView,
                                      boolean success) {
        return new AgentToolResult(turnId, toolUseId, name, content, toolResultView, success, Instant.now());
    }
}
