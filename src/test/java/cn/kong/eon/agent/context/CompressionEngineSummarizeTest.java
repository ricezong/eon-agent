package cn.kong.eon.agent.context;

import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CompressionEngine Summarize 路径的专用测试。
 * 使用 FakeLlmClient 覆盖 applySummarize 的成功路径和降级路径。
 */
class CompressionEngineSummarizeTest {

    private CompressionEngine createEngineWith(LlmClient fakeLlm) {
        return new CompressionEngine(0.5, 0.75, 0.95, 100, 50000, 2000, fakeLlm, "/test/transcript.jsonl");
    }

    private List<ChatMessage> buildDialogForSummary(int count) {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(UserMessage.from("user message " + i));
            messages.add(AiMessage.from("assistant reply " + i));
        }
        return messages;
    }

    /**
     * 成功路径：LLM 返回有效摘要，旧消息被删除，摘要被保存。
     */
    @Test
    void compress_atSummarizeThreshold_summarizesAndRemovesOldMessages() {
        // Build enough messages so that tailStart > 0
        // tailGuardTurns=0 -> tailStart = max(0, count - 0*2 - 2) = count - 2
        // Need count > 2 so tailStart > 0
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10)); // 20 messages
        // tailStart = 20 - 2 = 18, so messages 0..17 are eligible for summarize
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient("## Summary\n1. Primary Request: test\n2. Key Context: none");
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0); // waterLevel >= summarizeThreshold(0.95)

        // LLM should have been called once for summary
        assertThat(fakeLlm.getCallCount()).isEqualTo(1);

        // Old messages should be removed (18 removed, 2 remain in tail guard)
        assertThat(messages).hasSize(2);

        // Summary should be stored in state
        assertThat(state.getLastSummary()).isNotNull();
        assertThat(state.getLastSummary()).contains("Summary");
        assertThat(state.getSummarizedUpToIndex()).isEqualTo(18);
    }

    /**
     * 增量摘要：已有旧摘要时，旧摘要 + 新对话一起送 LLM。
     */
    @Test
    void compress_incrementalSummary_passesExistingSummary() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();
        state.setLastSummary("## Old Summary\n1. Previous context");

        FakeLlmClient fakeLlm = new FakeLlmClient("## New Merged Summary");
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        // The prompt sent to LLM should contain the old summary
        assertThat(fakeLlm.getLastPromptText()).contains("Old Summary");
        assertThat(state.getLastSummary()).contains("New Merged Summary");
    }

    /**
     * 摘要超长截断：LLM 返回的摘要超过 maxOutputChars 时被截断。
     */
    @Test
    void compress_summaryTooLong_truncatedToMaxOutputChars() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();

        String longSummary = "S".repeat(3000); // exceeds summarizeMaxOutputChars=2000
        FakeLlmClient fakeLlm = new FakeLlmClient(longSummary);
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        // Summary should be truncated to 2000 chars + "..." (2003 total)
        assertThat(state.getLastSummary().length()).isEqualTo(2003);
        assertThat(state.getLastSummary()).endsWith("...");
    }

    /**
     * 降级路径：LLM 返回空摘要时不删除消息。
     */
    @Test
    void compress_emptySummary_skipsRemoval() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient(""); // empty summary
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        // Empty summary -> skip removal
        assertThat(state.getLastSummary()).isNull();
        assertThat(messages).hasSize(20); // unchanged
    }

    /**
     * 降级路径：LLM 返回 null 摘要不删除消息。
     */
    @Test
    void compress_nullSummary_skipsRemoval() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient(null);
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        assertThat(state.getLastSummary()).isNull();
        assertThat(messages).hasSize(20);
    }

    /**
     * 降级路径：LLM 抛异常时降级为 Prune（删除旧消息，不保存摘要）。
     */
    @Test
    void compress_llmThrowsException_degradesToPrune() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient((String) null);
        fakeLlm.setThrowException(true);
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        // Fallback: messages are still removed, but no summary stored
        assertThat(messages).hasSize(2); // 18 removed, 2 remain
        assertThat(state.getLastSummary()).isNull();
        // summarizedUpToIndex should still be set (prevents retry)
        assertThat(state.getSummarizedUpToIndex()).isEqualTo(18);
    }

    /**
     * 幂等性：已摘要到某个索引后不重复摘要。
     */
    @Test
    void compress_alreadySummarized_doesNotRetry() {
        List<ChatMessage> messages = new ArrayList<>(buildDialogForSummary(10));
        CompressionState state = new CompressionState();
        state.setSummarizedUpToIndex(18); // already summarized up to tailStart

        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        // LLM should not be called again
        assertThat(fakeLlm.getCallCount()).isZero();
    }

    /**
     * tail guard 保护：tailStart=0 时不执行摘要。
     */
    @Test
    void compress_noMessagesOutsideTailGuard_skipsSummarize() {
        // 2 messages, tailGuardTurns=0 -> tailStart = max(0, 2-2) = 0
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("only message"),
                AiMessage.from("reply")
        ));
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        assertThat(fakeLlm.getCallCount()).isZero();
        assertThat(messages).hasSize(2);
    }

    /**
     * 无对话文本时跳过摘要（全是 SystemMessage）。
     */
    @Test
    void compress_noDialogText_skipsSummarize() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from("system prompt 1"),
                SystemMessage.from("system prompt 2"),
                SystemMessage.from("system prompt 3"),
                UserMessage.from("tail"),
                AiMessage.from("tail reply")
        ));
        // tailStart = max(0, 5-2) = 3, messages 0..2 are SystemMessages (formatMessageForSummary returns null)
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");
        CompressionEngine engine = createEngineWith(fakeLlm);

        engine.compress(messages, state, 0.96, 0);

        assertThat(fakeLlm.getCallCount()).isZero();
    }

    // ===== Fake LlmClient =====

    /**
     * FakeLlmClient — 覆盖 chat() 方法返回预设摘要。
     * 父类构造器需要一个 AgentConfig，但 OpenAiChatModel.builder() 只构建配置不发起网络连接，
     * 所以传入 dummy config 是安全的。
     */
    static class FakeLlmClient extends LlmClient {
        private final String summaryResponse;
        private boolean throwException = false;
        private int callCount = 0;
        private String lastPromptText = "";

        FakeLlmClient(String summaryResponse) {
            super(createDummyConfig());
            this.summaryResponse = summaryResponse;
        }

        @Override
        public LlmResponse chat(List<ChatMessage> messages, List<ToolSpecification> tools) {
            if (throwException) {
                throw new RuntimeException("LLM unavailable");
            }
            callCount++;
            // Capture the prompt text for assertions
            for (ChatMessage msg : messages) {
                if (msg instanceof UserMessage um) {
                    lastPromptText = um.singleText();
                    break;
                }
            }
            AiMessage aiMsg = summaryResponse != null
                    ? AiMessage.from(summaryResponse)
                    : AiMessage.from("");
            return LlmResponse.of(aiMsg, TokenUsage.zero(), "STOP");
        }

        int getCallCount() { return callCount; }
        String getLastPromptText() { return lastPromptText; }
        void setThrowException(boolean v) { this.throwException = v; }
    }

    private static cn.kong.eon.config.AgentConfig createDummyConfig() {
        cn.kong.eon.config.AgentConfig config = new cn.kong.eon.config.AgentConfig();
        // Set minimal LlmConfig to avoid NPE in parent constructor
        cn.kong.eon.config.AgentConfig.LlmConfig llm = new cn.kong.eon.config.AgentConfig.LlmConfig();
        llm.setApiKey("dummy-key");
        llm.setBaseUrl("http://localhost:0/v1");
        llm.setModelName("dummy-model");
        llm.setTimeout(1);
        llm.setMaxTokens(1);
        try {
            var llmField = cn.kong.eon.config.AgentConfig.class.getDeclaredField("llm");
            llmField.setAccessible(true);
            llmField.set(config, llm);

            var retryField = cn.kong.eon.config.AgentConfig.class.getDeclaredField("retry");
            retryField.setAccessible(true);
            cn.kong.eon.config.AgentConfig.RetryConfig retry = new cn.kong.eon.config.AgentConfig.RetryConfig();
            retry.setAttempts(1);
            retryField.set(config, retry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return config;
    }
}
