package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.CompressionState;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Snip / Prune 两条有损规则的策略机测试。承接原 {@code CompressionEngineTest} 的断言。
 * <p>
 * 与旧实现的两处关键差异，这里逐条钉死：
 * <ol>
 *   <li>保护区按<b>轮次</b>计算，不再用 {@code size - turns*2 - 2} 近似</li>
 *   <li>作用对象由 {@code Retention} 标签决定，不再靠 {@code instanceof} 特判</li>
 * </ol>
 */
class SnipPrunePolicyTest {

    private static final long CONTEXT_MAX = 100_000L;
    private static final int SNIP_KEEP_CHARS = 200;
    private static final int SUMMARIZE_TURNS = 100;  // 足够大，确保轮数触发不干扰水位触发
    private static final int CONTENT_CHARS = 500;

    private ContextPolicy policy() {
        return new ContextPolicy(
                List.of(new SnipRule(0.5, SUMMARIZE_TURNS, SNIP_KEEP_CHARS),
                        new PruneRule(0.75, SUMMARIZE_TURNS)),
                0.05);
    }

    /** 10 条工具结果，轮次 0..9，latestTurn = 9 */
    private ContextWindow windowWithToolResults(int count, String content) {
        ContextWindow window = new ContextWindow();
        for (int i = 0; i < count; i++) {
            window.addAll(BlockProjector.explode(
                    ToolExecutionResultMessage.from("c" + i, "read_file", content),
                    "g" + i, i, null));
        }
        return window;
    }

    private ContextMetrics atWater(double waterLevel) {
        return ContextMetrics.builder()
                .transcriptTokens((long) (waterLevel * CONTEXT_MAX))
                .contextMaxTokens(CONTEXT_MAX)
                .build();
    }

    private ContextBlock block(ContextWindow window, String callId) {
        return window.view().stream()
                .filter(b -> callId.equals(b.toolCallId()))
                .findFirst()
                .orElseThrow();
    }

    private PolicyResult run(ContextWindow window, double water, int tailGuard) {
        return policy().runEligible(window, atWater(water), new CompressionState(),
                0, tailGuard, window.latestTurn());
    }

    // ═══════════════════ 未达阈值 ═══════════════════

    @Test
    void belowSnipThreshold_doesNothing() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        PolicyResult result = run(window, 0.3, 2);

        assertThat(result.applied()).isFalse();
        assertThat(window.view()).noneMatch(ContextBlock::isSnipped);
    }

    // ═══════════════════ Snip ═══════════════════

    @Test
    void atSnipThreshold_snipsOldToolResults() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        // tailGuard=2 → cutoffTurn = 9-2 = 7 → 轮次 0..6 参与
        PolicyResult result = run(window, 0.5, 2);

        assertThat(result.applied()).isTrue();
        assertThat(result.stages()).contains("Snip×7");

        for (int i = 0; i <= 6; i++) {
            assertThat(block(window, "c" + i).isSnipped()).isTrue();
        }
        for (int i = 7; i <= 9; i++) {
            assertThat(block(window, "c" + i).isSnipped()).isFalse();
        }

        ContextBlock snipped = block(window, "c0");
        assertThat(snipped.chars()).isLessThan(CONTENT_CHARS);
        assertThat(snipped.text()).contains("[中间内容已省略");
    }

    @Test
    void tailGuard_protectsRecentTurns() {
        // tailGuard=4 → cutoffTurn = 5 → 轮次 0..4 参与
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        run(window, 0.5, 4);

        assertThat(block(window, "c4").isSnipped()).isTrue();
        assertThat(block(window, "c5").isSnipped()).isFalse();
    }

    @Test
    void snip_isIdempotent() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        run(window, 0.5, 2);
        long charsAfterFirst = window.totalChars();
        PolicyResult second = run(window, 0.5, 2);

        assertThat(second.applied()).isFalse();
        assertThat(window.totalChars()).isEqualTo(charsAfterFirst);
    }

    @Test
    void snip_preservesArtifactReference() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));
        block(window, "c0").setRefId("art_001");

        run(window, 0.5, 2);

        assertThat(block(window, "c0").text()).contains("art_001");
    }

    // ═══════════════════ Prune ═══════════════════

    @Test
    void atPruneThreshold_executesSnipThenPrune() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        PolicyResult result = run(window, 0.8, 2);

        assertThat(result.stages()).contains("Snip×7", "Prune×7");
        assertThat(block(window, "c0").isPruned()).isTrue();
        assertThat(block(window, "c0").isSnipped()).isTrue();   // Prune 隐含 Snip
        assertThat(block(window, "c0").text()).contains("[旧工具结果内容已清除");
        assertThat(block(window, "c7").isPruned()).isFalse();
    }

    @Test
    void atSnipThresholdOnly_snipsButNotPrunes() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));

        run(window, 0.5, 2);

        assertThat(block(window, "c0").isSnipped()).isTrue();
        assertThat(block(window, "c0").isPruned()).isFalse();
    }

    @Test
    void prune_keepsArtifactReferenceAsPointerToDiskCopy() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));
        block(window, "c0").setRefId("art_007");

        run(window, 0.8, 2);

        // 已落盘的块替换占位符时保留引用 → 内容可从磁盘取回，实际无损
        assertThat(block(window, "c0").text()).isEqualTo("[旧工具结果内容已清除。引用: art_007]");
    }

    // ═══════════════════ 逐字保留 ═══════════════════

    /**
     * 有损规则不该碰用户输入。过去这条约束只能寄望 LLM 在摘要 prompt 里自觉执行，
     * 现在是 {@code Retention} 标签的结构性保证。
     */
    @Test
    void lossyRules_neverTouchVerbatimUserInput() {
        ContextWindow window = windowWithToolResults(10, "x".repeat(CONTENT_CHARS));
        window.addAll(BlockProjector.explode(
                UserMessage.from("这条早期用户消息必须逐字保留"), "gu", 0, null));

        run(window, 0.8, 0);

        ContextBlock user = window.view().stream()
                .filter(b -> b.kind() == cn.kong.eon.agent.context.block.BlockKind.USER_INPUT)
                .findFirst()
                .orElseThrow();
        assertThat(user.text()).isEqualTo("这条早期用户消息必须逐字保留");
        assertThat(user.isSnipped()).isFalse();
        assertThat(user.isPruned()).isFalse();
    }

    // ═══════════════════ 压缩充分性 ═══════════════════

    /**
     * 压完降幅不足时升级档位，让下一轮的轮数触发可以跨到更高级别。
     * 旧实现没有这个反馈回路，压不动时只会原地打转。
     */
    @Test
    void insufficientReduction_escalatesLevel() {
        // 内容极短：Snip 无块可截（chars <= snipKeepChars 直接跳过），降幅为 0
        ContextWindow window = windowWithToolResults(10, "short");

        ContextPolicy policy = policy();
        policy.runEligible(window, atWater(0.8), new CompressionState(), 0, 2, window.latestTurn());

        assertThat(policy.getEscalateLevel()).isGreaterThan(0);
    }
}
