package cn.kong.eon.event;

import java.time.Instant;

/**
 * 会话执行错误事件。与 SSE 层的 event:error 不同，后者是流本身错误并会关闭连接。
 */
public record SessionError(
        String message,
        String type,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.error";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitError(this);
    }

    public static SessionError now(String message, String type) {
        return new SessionError(message, type, Instant.now());
    }
}
