package cn.kong.eon.event;

import java.time.Instant;

/**
 * 会话状态变更事件。公开状态 idle/running/terminated。
 * 回到 idle 表示本轮结束，可能带 stop_reason。
 */
public record SessionStatus(
        String status,        // "running" | "idle" | "terminated"
        String stopReason,    // idle 时可能携带
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.status";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitStatus(this);
    }

    public static SessionStatus running() {
        return new SessionStatus("running", null, Instant.now());
    }

    public static SessionStatus idle(String stopReason) {
        return new SessionStatus("idle", stopReason, Instant.now());
    }

    public static SessionStatus terminated(String stopReason) {
        return new SessionStatus("terminated", stopReason, Instant.now());
    }
}
