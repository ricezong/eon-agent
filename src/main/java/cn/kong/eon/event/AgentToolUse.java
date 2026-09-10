package cn.kong.eon.event;

import java.time.Instant;

/**
 * 模型请求调用工具事件。含 tool_use.id/name/input。
 */
public record AgentToolUse(
        String turnId,
        String toolUseId,
        String name,
        String input,
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "agent.tool_use";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitToolUse(this);
    }

    public static AgentToolUse now(String turnId, String toolUseId, String name, String input) {
        return new AgentToolUse(turnId, toolUseId, name, input, Instant.now());
    }
}
