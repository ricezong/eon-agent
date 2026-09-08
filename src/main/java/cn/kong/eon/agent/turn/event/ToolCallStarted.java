package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * 工具调用开始事件。前端渲染：工具调用状态条（Loading）。
 */
public record ToolCallStarted(
        String toolName,
        String args,
        int turn,
        Instant timestamp
) implements TurnEvent {
    public static ToolCallStarted now(String toolName, String args, int turn) {
        return new ToolCallStarted(toolName, args, turn, Instant.now());
    }
}
