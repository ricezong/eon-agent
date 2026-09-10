package cn.kong.eon.context.port;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 上下文层对 LLM 层的依赖倒置接口。由 LlmClient 实现并注入。
 */
public interface LlmSupport {

    /**
     * 无工具调用，请求模型生成文本回复。
     *
     * @return 模型回复文本；失败时抛出 RuntimeException
     */
    String complete(List<ChatMessage> messages);
}
