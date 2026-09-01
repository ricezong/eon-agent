package cn.kong.eon.llm;

import cn.kong.eon.model.TokenUsage;
import dev.langchain4j.data.message.AiMessage;

/**
 * LLM 响应封装，包含 AI 消息、Token 用量和结束原因。
 */
public record LlmResponse(
        AiMessage aiMessage,
        TokenUsage usage,
        String finishReason
) {
    /** 创建 LlmResponse 实例。 */
    public static LlmResponse of(AiMessage aiMessage, TokenUsage usage, String finishReason) {
        return new LlmResponse(aiMessage, usage, finishReason);
    }
}
