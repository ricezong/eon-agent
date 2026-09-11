package cn.kong.eon.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 对外暴露的 Service 接口。
 * <p>
 * 引擎调用 {@link #chat} / {@link #streamChat} 做带工具的对话，
 * 摘要器调用 {@link #complete} 做纯文本补全。
 * 由 {@link LlmClient} 实现并注入。
 */
public interface LlmService {

    /**
     * 无工具调用，请求模型生成文本回复。
     *
     * @return 模型回复文本；失败时抛出 {@link LlmStalledException}
     */
    String complete(List<ChatMessage> messages);

    /**
     * 同步调用 LLM（带工具），含指数退避重试。
     *
     * @return 完整的 LLM 响应
     */
    LlmResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools);

    /**
     * 流式调用 LLM（带工具）。
     *
     * @param messages          上下文消息
     * @param tools             工具规格
     * @param onTextDelta       文本增量回调
     * @param onThinkingDelta   thinking 增量回调
     * @param onThinkingComplete thinking 完成回调
     * @return 完整的 LLM 响应
     */
    LlmResponse streamChat(List<ChatMessage> messages, List<ToolSpecification> tools,
                           Consumer<String> onTextDelta,
                           Consumer<String> onThinkingDelta,
                           Consumer<String> onThinkingComplete);

    /** 是否启用流式。 */
    boolean isStreamEnabled();
}
