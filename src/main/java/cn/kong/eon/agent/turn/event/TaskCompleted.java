package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * 任务正常完成事件。前端渲染：结束标记、最终摘要。
 */
public record TaskCompleted(
        String output,
        int turns,
        long totalTokens,
        Instant timestamp
) implements TurnEvent {
    public static TaskCompleted now(String output, int turns, long totalTokens) {
        return new TaskCompleted(output, turns, totalTokens, Instant.now());
    }
}
