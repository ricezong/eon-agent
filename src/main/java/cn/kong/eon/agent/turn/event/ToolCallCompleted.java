package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * 工具调用完成事件。前端渲染：更新工具调用状态条（成功/失败）。
 */
public record ToolCallCompleted(
        String toolName,
        String args,
        boolean success,
        String output,
        int outputLength,
        int turn,
        Instant timestamp
) implements TurnEvent {
    public static ToolCallCompleted now(String toolName, String args, boolean success,
                                        String output, int outputLength, int turn) {
        return new ToolCallCompleted(toolName, args, success, output, outputLength, turn, Instant.now());
    }
}
