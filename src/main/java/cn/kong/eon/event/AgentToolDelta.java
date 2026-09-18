package cn.kong.eon.event;

import java.time.Instant;

/**
 * 工具参数生成增量事件。只在流式模式出现，历史列表不保留。
 * 模型生成工具入参（例如整个文件内容）的过程可能持续数十秒且没有文本增量，
 * 本事件让前端在这段静默期仍能反馈进度。
 */
public record AgentToolDelta(
        String turnId,
        int index,
        String toolUseId,
        String name,
        String delta,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "engine.tool_delta";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitToolDelta(this);
    }

    public static AgentToolDelta now(String turnId, int index, String toolUseId, String name, String delta) {
        return new AgentToolDelta(turnId, index, toolUseId, name, delta, Instant.now());
    }
}
