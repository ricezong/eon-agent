package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * 对话开始事件。前端渲染：会话头、用户输入气泡。
 */
public record MessageStarted(
        String sessionId,
        String userInput,
        Instant timestamp
) implements TurnEvent {
    public static MessageStarted now(String sessionId, String userInput) {
        return new MessageStarted(sessionId, userInput, Instant.now());
    }
}
