package cn.kong.eon.event;

import java.time.Instant;

/**
 * 本轮聚合的 token 用量统计事件。
 */
public record SessionUsage(
        String turnId,
        String messageId,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.usage";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitUsage(this);
    }

    public static SessionUsage now(String turnId, String messageId,
                                   int promptTokens, int completionTokens, int totalTokens) {
        return new SessionUsage(turnId, messageId, promptTokens, completionTokens, totalTokens, Instant.now());
    }
}
