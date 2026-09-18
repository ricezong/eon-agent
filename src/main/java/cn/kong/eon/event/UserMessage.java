package cn.kong.eon.event;

import java.time.Instant;

/**
 * 用户消息事件。实时对话中用户消息由前端本地渲染（不经过 SSE），
 * 本事件只在账本回放（GET /api/sessions/{id}）时产出，
 * 用于刷新/恢复会话时还原用户侧消息。
 */
public record UserMessage(
        String content,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "user.message";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitUserMessage(this);
    }

    public static UserMessage now(String content) {
        return new UserMessage(content, Instant.now());
    }
}
