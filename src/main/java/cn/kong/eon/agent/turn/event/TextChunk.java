package cn.kong.eon.agent.turn.event;

import cn.kong.eon.agent.turn.TurnEvent;

import java.time.Instant;

/**
 * LLM 文本输出事件。每个 Turn 产生一条。
 * 前端渲染：助手回复气泡（非流式，一次性渲染全文）。
 */
public record TextChunk(
        String text,
        Instant timestamp
) implements TurnEvent {
    public static TextChunk now(String text) {
        return new TextChunk(text, Instant.now());
    }
}
