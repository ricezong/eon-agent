package cn.kong.eon.agent.event;

import java.time.Instant;

/**
 * 完整思考块事件。配合 agent.delta(kind=thinking) 实时展示。
 */
public record AgentThinking(
        String turnId,
        String content,
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "agent.thinking";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitThinking(this);
    }

    public static AgentThinking now(String turnId, String content) {
        return new AgentThinking(turnId, content, Instant.now());
    }
}
