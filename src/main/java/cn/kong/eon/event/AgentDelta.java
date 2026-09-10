package cn.kong.eon.event;

import java.time.Instant;

/**
 * 实时增量事件。只在流式模式出现，历史列表不保留。
 */
public record AgentDelta(
        String turnId,
        String kind,          // "text" | "thinking"
        String delta,
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
        return new AgentDelta(turnId, "text", delta, Instant.now());
    }

    public static AgentDelta thinking(String turnId, String delta) {
        return new AgentDelta(turnId, "thinking", delta, Instant.now());
    }
}
