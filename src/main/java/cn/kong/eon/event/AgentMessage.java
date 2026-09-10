package cn.kong.eon.event;

import java.time.Instant;
import java.util.List;

/**
 * 完整回答事件。content 为 parts 数组，常见 {"type":"text","text":...}。
 */
public record AgentMessage(
        String turnId,
        String messageId,
        List<ContentPart> content,
        Instant timestamp
) implements TurnEvent {

    @Override
    public String type() {
        return "agent.message";
    }

    @Override
    public <T> T accept(TurnEventVisitor<T> visitor) {
        return visitor.visitMessage(this);
    }

    public static AgentMessage now(String turnId, String messageId, String text) {
        return new AgentMessage(turnId, messageId, List.of(ContentPart.text(text)), Instant.now());
    }
}
