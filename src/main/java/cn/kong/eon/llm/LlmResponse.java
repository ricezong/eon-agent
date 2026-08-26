package cn.kong.eon.llm;

import cn.kong.eon.model.TokenUsage;
import dev.langchain4j.data.message.AiMessage;

/**
 * LLM 响应封装。
 */
public record LlmResponse(
        AiMessage aiMessage,
        TokenUsage usage,
        String finishReason
) {
    public static LlmResponse of(AiMessage aiMessage, TokenUsage usage, String finishReason) {
        return new LlmResponse(aiMessage, usage, finishReason);
    }
}
