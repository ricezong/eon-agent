package cn.kong.eon.agent.event;

import java.time.Instant;

/**
 * 模型请求调用工具事件。含 tool_use.id/name/input 与 evaluated_permission。
 */
public record AgentToolUse(
        String turnId,
        String toolUseId,
        String name,
        String input,
        String evaluatedPermission,
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

    public static AgentToolUse now(String turnId, String toolUseId, String name,
                                   String input, String evaluatedPermission) {
        return new AgentToolUse(turnId, toolUseId, name, input, evaluatedPermission, Instant.now());
    }
}
