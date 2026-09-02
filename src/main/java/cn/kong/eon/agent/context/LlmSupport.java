package cn.kong.eon.agent.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 上下文层对 LLM 层的依赖倒置接口。
 * context 包需要让模型生成文本（如对话摘要），但不应该因此依赖 llm 包的任何具体类；
 * 由 {@code LlmClient} 实现本接口并在装配期注入。
 */
public interface LlmSupport {

    /**
     * 无工具调用，请求模型生成文本回复。
     *
     * @return 模型回复文本；模型未返回文本时为 null
     * @throws RuntimeException 重试耗尽或不可重试失败时抛出，由调用方决定降级策略
     */
    String complete(List<ChatMessage> messages);
}
