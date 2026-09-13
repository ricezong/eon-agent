package cn.kong.eon.event;

import java.time.Instant;

/**
 * 事件流首帧，把服务端确定的会话身份交付客户端。
 */
public record SessionStart(
        String sessionId,
        String title,      // 会话标题；回放时为 null
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.start";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitSessionStart(this);
    }

    public static SessionStart now(String sessionId, String title) {
        return new SessionStart(sessionId, title, Instant.now());
    }
}
