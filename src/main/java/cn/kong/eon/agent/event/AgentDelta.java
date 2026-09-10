package cn.kong.eon.agent.event;

import java.time.Instant;

/**
 * 实时增量事件。只在流式模式出现，历史列表不保留。
 */
public record AgentDelta(
        String turnId,
        String kind,          // "text" | "thinking" | "tool_use"
        String delta,          // text/thinking 的增量文本
        String inputDelta,     // tool_use 的参数增量 JSON
        String toolUseId,       // kind=tool_use 时关联的 id
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "agent.delta";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitDelta(this);
    }

    public static AgentDelta text(String turnId, String delta) {
        return new AgentDelta(turnId, "text", delta, null, null, Instant.now());
    }

    public static AgentDelta thinking(String turnId, String delta) {
        return new AgentDelta(turnId, "thinking", delta, null, null, Instant.now());
    }

    public static AgentDelta toolUse(String turnId, String toolUseId, String inputDelta) {
        return new AgentDelta(turnId, "tool_use", null, inputDelta, toolUseId, Instant.now());
    }
}
