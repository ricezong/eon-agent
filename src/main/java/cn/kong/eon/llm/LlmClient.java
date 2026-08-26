package cn.kong.eon.llm;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * LLM 客户端封装，使用 LangChain4j OpenAiChatModel，支持指数退避重试。
 */
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ChatModel chatModel;
    private final AgentConfig.RetryConfig retryConfig;
    private final TokenCountEstimator tokenCountEstimator;

    public LlmClient(AgentConfig config) {
        AgentConfig.LlmConfig llmConfig = config.getLlm();
        this.retryConfig = config.getRetry();

        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(llmConfig.getBaseUrl())
                .apiKey(llmConfig.getApiKey())
                .modelName(llmConfig.getModelName())
                .temperature(llmConfig.getTemperature())
                .maxTokens(llmConfig.getMaxTokens())
                .timeout(Duration.ofSeconds(llmConfig.getTimeout()))
                .logRequests(false)
                .logResponses(false)
                .build();

        // 创建精确的 Token 估算器；若模型名不被 JTokkit 识别则回退到 gpt-4o 编码
        TokenCountEstimator estimator;
        try {
            estimator = new OpenAiTokenCountEstimator(llmConfig.getModelName());
        } catch (Exception e) {
            log.warn("为模型 '{}' 创建 tokenizer 失败，回退到 gpt-4o: {}",
                    llmConfig.getModelName(), e.getMessage());
            estimator = new OpenAiTokenCountEstimator("gpt-4o");
        }
        this.tokenCountEstimator = estimator;

        log.info("LlmClient 已初始化: provider={}, model={}, baseUrl={}",
                llmConfig.getProvider(), llmConfig.getModelName(), llmConfig.getBaseUrl());
    }

    /**
     * 暴露 TokenCountEstimator 供 ContextBuilder 做精确估算。
     */
    public TokenCountEstimator getTokenCountEstimator() {
        return tokenCountEstimator;
    }

    /**
     * 调用 LLM，带指数退避重试。
     */
    public LlmResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < retryConfig.getAttempts()) {
            try {
                ChatRequest.Builder requestBuilder = ChatRequest.builder()
                        .messages(messages);

                if (tools != null && !tools.isEmpty()) {
                    requestBuilder.toolSpecifications(tools);
                }

                ChatRequest request = requestBuilder.build();
                ChatResponse response = chatModel.chat(request);

                AiMessage aiMessage = response.aiMessage();
                TokenUsage usage = new TokenUsage();
                if (response.tokenUsage() != null) {
                    usage.setPromptTokens(response.tokenUsage().inputTokenCount());
                    usage.setCompletionTokens(response.tokenUsage().outputTokenCount());
                    usage.setTotalTokens(response.tokenUsage().totalTokenCount());
                }

                String finishReason = response.finishReason() != null ? response.finishReason().name() : "STOP";

                log.debug("LLM 响应: text={}, toolCalls={}, usage={}",
                        aiMessage.text() != null ? aiMessage.text().length() : 0,
                        aiMessage.hasToolExecutionRequests() ? aiMessage.toolExecutionRequests().size() : 0,
                        usage);

                return LlmResponse.of(aiMessage, usage, finishReason);

            } catch (NonRetriableException e) {
                // 不可重试异常（401 认证失败 / 400 请求格式错误等），立即失败
                log.error("LLM 调用失败（不可重试）: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                throw new LlmStalledException("LLM 调用失败（不可重试）: " + e.getMessage());
            } catch (Exception e) {
                lastException = e;
                attempt++;
                log.warn("LLM 调用失败（尝试 {}/{}）: {} - {}", attempt, retryConfig.getAttempts(),
                        e.getClass().getSimpleName(), e.getMessage());

                if (attempt < retryConfig.getAttempts()) {
                    long delay = calculateDelay(attempt);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM 调用被中断", ie);
                    }
                }
            }
        }

        log.error("LLM_STALLED: 模型连续 {} 次调用失败", retryConfig.getAttempts(), lastException);
        throw new LlmStalledException("LLM 调用连续失败 " + retryConfig.getAttempts() + " 次，模型不可用");
    }

    private long calculateDelay(int attempt) {
        long base = (long) (retryConfig.getMinDelayMs() * Math.pow(2, attempt - 1));
        long jitter = (long) (base * retryConfig.getJitter() * (Math.random() - 0.5) * 2);
        return Math.min(retryConfig.getMaxDelayMs(), base + jitter);
    }
}
