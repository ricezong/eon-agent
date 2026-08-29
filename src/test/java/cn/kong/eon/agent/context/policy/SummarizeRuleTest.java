package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.TokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summarize 路径测试。承接原 {@code CompressionEngineSummarizeTest}。
 * <p>
 * 与旧实现最重要的一处行为差异在这里钉死：<b>用户消息逐字保留</b>。
 * 过去删除走 {@code subList(0, n).clear()}，早期用户消息会被连锅端掉；
 * 现在走 {@link ContextWindow#removeBefore(int)}，逐字块由标签自动保留。
 */
class SummarizeRuleTest {

    private static final long CONTEXT_MAX = 100_000L;
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final String TRANSCRIPT_PATH = "/test/transcript.jsonl";

    private ContextPolicy policyWith(LlmClient fakeLlm) {
        SummarizeRule rule = new SummarizeRule(0.95, 100, 50000, MAX_OUTPUT_CHARS,
                fakeLlm, TRANSCRIPT_PATH);
        return new ContextPolicy(List.of(rule), 0.05);
    }

    private ContextMetrics atWater(double waterLevel) {
        return new ContextMetrics(
                (long) (waterLevel * CONTEXT_MAX), 0, 0, 0,
                CONTEXT_MAX, 0, 0, java.util.Map.of());
    }

    /** 10 组 user+ai，轮次 0..9 → latestTurn = 9 */
    private ContextWindow dialogWindow(int pairs) {
        ContextWindow window = new ContextWindow();
        for (int i = 0; i < pairs; i++) {
            window.addAll(BlockProjector.explode(UserMessage.from("user message " + i), "u" + i, i, null));
            window.addAll(BlockProjector.explode(AiMessage.from("assistant reply " + i), "a" + i, i, null));
        }
        return window;
    }

    private PolicyResult run(ContextPolicy policy, ContextWindow window, CompressionState state) {
        return policy.runEligible(window, atWater(0.96), state, 0, 0, window.latestTurn());
    }

    // ═══════════════════ 成功路径 ═══════════════════

    @Test
    void summarize_removesCompressibleBlocks_butKeepsVerbatimUserInput() {
        ContextWindow window = dialogWindow(10);
        assertThat(window.size()).isEqualTo(20);   // 10 USER_INPUT + 10 AI_TEXT

        FakeLlmClient fakeLlm = new FakeLlmClient("## Summary\n1. Primary Request: test");
        CompressionState state = new CompressionState();

        // tailGuard=0 → cutoffTurn = 9 → 轮次 0..8 的可压缩块参与
        PolicyResult result = run(policyWith(fakeLlm), window, state);

        assertThat(result.applied()).isTrue();
        assertThat(fakeLlm.getCallCount()).isEqualTo(1);
        assertThat(state.getLastSummary()).contains("Summary");

        // AI 正文（COMPRESSIBLE）只剩轮次 9 那一条
        assertThat(window.view()).filteredOn(b -> b.kind() == BlockKind.AI_TEXT).hasSize(1);
        // 关键改进：所有用户消息（VERBATIM）一条不丢
        assertThat(window.view()).filteredOn(b -> b.kind() == BlockKind.USER_INPUT).hasSize(10);
        assertThat(state.getSummarizedMessageCount()).isEqualTo(9);
    }

    @Test
    void summarize_incrementalSummary_passesExistingSummaryToLlm() {
        ContextWindow window = dialogWindow(10);
        CompressionState state = new CompressionState();
        state.setLastSummary("## Old Summary\n1. Previous context");

        FakeLlmClient fakeLlm = new FakeLlmClient("## New Merged Summary");
        run(policyWith(fakeLlm), window, state);

        assertThat(fakeLlm.getLastPromptText()).contains("Old Summary");
        assertThat(state.getLastSummary()).contains("New Merged Summary");
    }

    @Test
    void summarize_tooLong_truncatedToMaxOutputChars() {
        ContextWindow window = dialogWindow(10);
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient("S".repeat(3000));
        run(policyWith(fakeLlm), window, state);

        assertThat(state.getLastSummary()).hasSize(MAX_OUTPUT_CHARS + 3);
        assertThat(state.getLastSummary()).endsWith("...");
    }

    // ═══════════════════ 降级路径 ═══════════════════

    /**
     * LLM 拿不到摘要时仍然回收空间，但会写入一条指向 transcript 的兜底说明。
     * <p>
     * 这与旧实现（空摘要 → 什么都不删）不同：旧做法会让上下文继续卡在高位，
     * 下一轮可能直接被 API 拒绝；新做法删掉的块可从磁盘账本取回，
     * 且兜底摘要明确告诉模型去哪里找。
     */
    @Test
    void summarize_emptySummary_fallsBackWithTranscriptPointer() {
        ContextWindow window = dialogWindow(10);
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient("");
        run(policyWith(fakeLlm), window, state);

        assertThat(state.getLastSummary()).contains("摘要生成失败");
        assertThat(state.getLastSummary()).contains(TRANSCRIPT_PATH);
        assertThat(window.view()).filteredOn(b -> b.kind() == BlockKind.AI_TEXT).hasSize(1);
    }

    @Test
    void summarize_llmThrows_fallsBackWithTranscriptPointer() {
        ContextWindow window = dialogWindow(10);
        CompressionState state = new CompressionState();

        FakeLlmClient fakeLlm = new FakeLlmClient((String) null);
        fakeLlm.setThrowException(true);
        run(policyWith(fakeLlm), window, state);

        assertThat(state.getLastSummary()).contains("摘要生成失败");
        assertThat(window.view()).filteredOn(b -> b.kind() == BlockKind.AI_TEXT).hasSize(1);
        assertThat(state.getSummarizedMessageCount()).isEqualTo(9);
    }

    // ═══════════════════ 跳过条件 ═══════════════════

    @Test
    void summarize_idempotent_secondRunFindsNothingRemovable() {
        ContextWindow window = dialogWindow(10);
        CompressionState state = new CompressionState();
        FakeLlmClient fakeLlm = new FakeLlmClient("## Summary");
        ContextPolicy policy = policyWith(fakeLlm);

        run(policy, window, state);
        run(policy, window, state);

        // 第一轮已把 cutoff 之前的可压缩块删完，第二轮无可删内容
        assertThat(fakeLlm.getCallCount()).isEqualTo(1);
    }

    @Test
    void summarize_noBlocksOutsideTailGuard_skips() {
        ContextWindow window = new ContextWindow();
        window.addAll(BlockProjector.explode(UserMessage.from("only message"), "u", 0, null));
        window.addAll(BlockProjector.explode(AiMessage.from("reply"), "a", 0, null));

        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");
        PolicyResult result = run(policyWith(fakeLlm), window, new CompressionState());

        assertThat(result.applied()).isFalse();
        assertThat(fakeLlm.getCallCount()).isZero();
    }

    @Test
    void summarize_onlyVerbatimBlocksOutsideTailGuard_skips() {
        ContextWindow window = new ContextWindow();
        window.addAll(BlockProjector.explode(SystemMessage.from("system prompt"), "s", 0, null));
        window.addAll(BlockProjector.explode(UserMessage.from("tail"), "u", 1, null));
        window.addAll(BlockProjector.explode(AiMessage.from("tail reply"), "a", 1, null));

        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");
        PolicyResult result = run(policyWith(fakeLlm), window, new CompressionState());

        assertThat(result.applied()).isFalse();
        assertThat(fakeLlm.getCallCount()).isZero();
    }

    @Test
    void summarize_belowThreshold_skips() {
        ContextWindow window = dialogWindow(10);
        FakeLlmClient fakeLlm = new FakeLlmClient("should not be called");

        ContextPolicy policy = policyWith(fakeLlm);
        policy.runEligible(window, atWater(0.5), new CompressionState(), 0, 0, window.latestTurn());

        assertThat(fakeLlm.getCallCount()).isZero();
    }

    // ═══════════════════ Fake LlmClient ═══════════════════

    /**
     * 覆盖 {@code chat()} 返回预设摘要。父类构造器只构建配置、不发起网络连接，
     * 因此传入 dummy config 是安全的。
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

        int getCallCount() {
            return callCount;
        }

        String getLastPromptText() {
            return lastPromptText;
        }

        void setThrowException(boolean v) {
            this.throwException = v;
        }
    }

    private static cn.kong.eon.config.AgentConfig createDummyConfig() {
        cn.kong.eon.config.AgentConfig config = new cn.kong.eon.config.AgentConfig();
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
