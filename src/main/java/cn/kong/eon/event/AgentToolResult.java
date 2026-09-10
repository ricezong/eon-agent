package cn.kong.eon.event;

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
        StructuredContent structuredContent,
        boolean success,
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "agent.tool_result";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitToolResult(this);
    }

    public static AgentToolResult now(String turnId, String toolUseId, String name,
                                      String content, StructuredContent structuredContent,
                                      boolean success) {
        return new AgentToolResult(turnId, toolUseId, name, content, structuredContent, success, Instant.now());
    }
}
