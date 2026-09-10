package cn.kong.eon.agent.event;

import java.time.Instant;

/**
 * 本轮聚合的 token 用量统计事件。
 * 建议固定在 agent.message 后、session.status=idle 前一次性下发。
 */
public record SessionUsage(
        String turnId,
        String messageId,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "session.usage";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitUsage(this);
    }

    public static SessionUsage now(String turnId, String messageId,
                                   int promptTokens, int completionTokens, int totalTokens) {
        return new SessionUsage(turnId, messageId, promptTokens, completionTokens, totalTokens, Instant.now());
    }
}
