package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * 任务被强制终止事件。前端渲染：终止原因、消耗统计。
 */
public record TaskStopped(
        String category,
        String reason,
        int turns,
        long totalTokens,
        Instant timestamp
) implements TurnEvent {
    public static TaskStopped now(String category, String reason, int turns, long totalTokens) {
        return new TaskStopped(category, reason, turns, totalTokens, Instant.now());
    }
}
