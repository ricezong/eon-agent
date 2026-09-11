package cn.kong.eon.event;

import java.time.Instant;

/**
 * 完整思考块事件。配合 agent.delta(kind=thinking) 实时展示。
 */
public record AgentThinking(
        String turnId,
        String content,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "agent.thinking";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitThinking(this);
    }

    public static AgentThinking now(String turnId, String content) {
        return new AgentThinking(turnId, content, Instant.now());
    }
}
