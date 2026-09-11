package cn.kong.eon.llm;

import cn.kong.eon.config.AgentConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.exception.NonRetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * LLM 客户端封装。基于 LangChain4j OpenAiChatModel，含指数退避重试。
 * 支持同步与流式两种调用模式。
 */
@Component
public class LlmClient implements LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final AgentConfig.RetryConfig retryConfig;
    private final OpenAiChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final boolean streamEnabled;

    public LlmClient(AgentConfig config) {
        AgentConfig.LlmConfig llmConfig = config.getLlm();
        this.retryConfig = config.getRetry();
        this.streamEnabled = llmConfig.isStreamEnabled();

        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .apiKey(llmConfig.getApiKey())
                .modelName(llmConfig.getModelName())
                .temperature(llmConfig.getTemperature())
                .maxTokens(llmConfig.getMaxTokens())
                .timeout(Duration.ofSeconds(llmConfig.getTimeout()))
                .returnThinking(true)
                .logRequests(false)
                .logResponses(false)
                .build();

        if (streamEnabled) {
            this.streamingChatModel = OpenAiStreamingChatModel.builder()
                    .baseUrl(llmConfig.getBaseUrl())
                    .apiKey(llmConfig.getApiKey())
                    .modelName(llmConfig.getModelName())
                    .temperature(llmConfig.getTemperature())
                    .maxTokens(llmConfig.getMaxTokens())
                    .timeout(Duration.ofSeconds(llmConfig.getTimeout()))
                    .returnThinking(true)
                    .build();
            log.info("LlmClient 已初始化（流式模式）: provider={}, model={}",
                    llmConfig.getProvider(), llmConfig.getModelName());
        } else {
            this.streamingChatModel = null;
            log.info("LlmClient 已初始化（同步模式）: provider={}, model={}",
                    llmConfig.getProvider(), llmConfig.getModelName());
        }
    }

    @Override
    public boolean isStreamEnabled() {
        return streamEnabled;
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < retryConfig.getAttempts()) {
            try {
                ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
                if (tools != null && !tools.isEmpty()) {
                    requestBuilder.toolSpecifications(tools);
                }
                ChatResponse response = chatModel.chat(requestBuilder.build());
                AiMessage aiMessage = response.aiMessage();
                TokenUsage usage = extractUsage(response);
                String finishReason = response.finishReason() != null ? response.finishReason().name() : "STOP";

                log.debug("LLM 响应: 文本长度={}, 工具调用={}, 用量={}",
                        aiMessage.text() != null ? aiMessage.text().length() : 0,
                        aiMessage.hasToolExecutionRequests() ? aiMessage.toolExecutionRequests().size() : 0,
                        usage);

                return LlmResponse.of(aiMessage, usage, finishReason);

            } catch (NonRetriableException e) {
                log.error("LLM 调用失败（不可重试）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                throw new LlmStalledException("LLM 调用失败（不可重试）: " + e.getMessage());
            } catch (Exception e) {
                lastException = e;
                attempt++;
                log.warn("LLM 调用失败（尝试 {}/{}）: {} - {}", attempt, retryConfig.getAttempts(),
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < retryConfig.getAttempts()) {
                    try {
                        Thread.sleep(calculateDelay(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM 调用被中断", ie);
                    }
                }
            }
        }

        log.error("LLM 调用连续失败 {} 次，模型不可用", retryConfig.getAttempts(), lastException);
        throw new LlmStalledException("LLM 调用连续失败 " + retryConfig.getAttempts() + " 次，模型不可用");
    }

    /**
     * 流式调用 LLM。
     *
     * @param messages       上下文消息
     * @param tools          工具规格
     * @param onTextDelta    文本增量回调
     * @param onThinkingDelta thinking 增量回调
     * @return 完整的 LLM 响应
     */
    @Override
    public LlmResponse streamChat(List<ChatMessage> messages, List<ToolSpecification> tools,
                                   Consumer<String> onTextDelta,
                                   Consumer<String> onThinkingDelta,
                                   Consumer<String> onThinkingComplete) {
        ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);
        if (tools != null && !tools.isEmpty()) {
            requestBuilder.toolSpecifications(tools);
        }
        ChatRequest request = requestBuilder.build();

        CompletableFuture<LlmResponse> future = new CompletableFuture<>();
        StringBuilder textBuilder = new StringBuilder();
        AtomicReference<AiMessage> aiMessageRef = new AtomicReference<>();
        AtomicReference<dev.langchain4j.model.output.TokenUsage> usageRef = new AtomicReference<>();
        AtomicReference<String> finishReasonRef = new AtomicReference<>("STOP");

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                textBuilder.append(partialResponse);
                if (onTextDelta != null) {
                    onTextDelta.accept(partialResponse);
                }
            }

            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                if (onThinkingDelta != null && partialThinking.text() != null) {
                    onThinkingDelta.accept(partialThinking.text());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                AiMessage ai = completeResponse.aiMessage();
                aiMessageRef.set(ai);
                usageRef.set(completeResponse.tokenUsage());

                if (onThinkingComplete != null && ai.thinking() != null && !ai.thinking().isBlank()) {
                    onThinkingComplete.accept(ai.thinking());
                }
                if (completeResponse.finishReason() != null) {
                    finishReasonRef.set(completeResponse.finishReason().name());
                }
                // 如果有工具调用但没文本，onPartialResponse 不会被调用
                if (ai.text() != null && !ai.text().isBlank() && onTextDelta != null) {
                    // 已通过增量推送，不需要再发
                }

                TokenUsage usage = new TokenUsage();
                var rawUsage = usageRef.get();
                if (rawUsage != null) {
                    usage.setPromptTokens(rawUsage.inputTokenCount());
                    usage.setCompletionTokens(rawUsage.outputTokenCount());
                    usage.setTotalTokens(rawUsage.totalTokenCount());
                }
                future.complete(LlmResponse.of(ai, usage, finishReasonRef.get()));
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        long timeoutSeconds = retryConfig.getAttempts() * 30L;
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmStalledException("LLM 流式调用超时（" + timeoutSeconds + "s 无响应）");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = e.getClass().getSimpleName();
            }
            throw new LlmStalledException("LLM 流式调用失败: " + msg);
        }
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        LlmResponse response = chat(messages, null);
        return response.aiMessage() != null ? response.aiMessage().text() : null;
    }

    /** 指数退避延迟，含随机抖动。 */
    private long calculateDelay(int attempt) {
        long base = (long) (retryConfig.getMinDelayMs() * Math.pow(2, attempt - 1));
        long jitter = (long) (base * retryConfig.getJitter() * (Math.random() - 0.5) * 2);
        return Math.min(retryConfig.getMaxDelayMs(), base + jitter);
    }

    private static TokenUsage extractUsage(ChatResponse response) {
        TokenUsage usage = new TokenUsage();
        if (response.tokenUsage() != null) {
            usage.setPromptTokens(response.tokenUsage().inputTokenCount());
            usage.setCompletionTokens(response.tokenUsage().outputTokenCount());
            usage.setTotalTokens(response.tokenUsage().totalTokenCount());
        }
        return usage;
    }
}
