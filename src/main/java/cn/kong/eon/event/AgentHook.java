package cn.kong.eon.event;

import java.time.Instant;

/**
 * PreModel 钩子开始事件。压缩在上下文组装时触发，只会发生在模型调用前；
 * 该阶段会再调一次 LLM 且期间没有任何文本增量可发，本事件用于告诉前端此刻卡在哪一步。
 */
public record AgentHook(
        String turnId,
        String hook,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "engine.hook";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitHook(this);
    }

    public static AgentHook now(String turnId, String hook) {
        return new AgentHook(turnId, hook, Instant.now());
    }
}
