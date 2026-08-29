package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文度量测试。
 * <p>
 * 补齐的两个口径（工具 schema、输出预留）本身就是这次发现的病灶之一：
 * 它们每轮都要随请求发出，却从未计入水位——
 * 100 轮下来是预算的 15%，而水位读数始终偏低，导致压缩迟迟不触发。
 */
class ContextMetricsTest {

    private static final long CONTEXT_MAX = 100_000L;

    private ContextMetrics metrics(long transcript, long anchor, long schema, long reserve,
                                   long budgetUsed, long budgetMax,
                                   Map<BlockKind, Long> byKind) {
        return new ContextMetrics(transcript, anchor, schema, reserve,
                CONTEXT_MAX, budgetUsed, budgetMax,
                byKind != null ? byKind : Map.of());
    }

    private ContextMetrics simple(long transcript) {
        return metrics(transcript, 0, 0, 0, 0, 0, null);
    }

    @Test
    void waterLevel_includesFixedPerTurnOverhead() {
        ContextMetrics m = metrics(50_000, 2_000, 5_000, 3_000, 0, 0, null);

        // 50k + 2k + 5k + 3k = 60k / 100k
        assertThat(m.sentTokens()).isEqualTo(60_000);
        assertThat(m.waterLevel()).isEqualTo(0.6);
    }

    @Test
    void waterLevel_ignoresFixedOverhead_whenNotProvided() {
        ContextMetrics m = simple(50_000);

        assertThat(m.waterLevel()).isEqualTo(0.5);
    }

    @Test
    void waterLevel_cappedAtOne() {
        ContextMetrics m = simple(200_000);

        assertThat(m.waterLevel()).isEqualTo(1.0);
    }

    // ═══════════════════ 预算投影 ═══════════════════

    @Test
    void projectedRemainingTurns_dividesRemainingBudgetByPerTurnCost() {
        ContextMetrics m = metrics(40_000, 0, 0, 0, 900_000, 1_000_000, null);

        // 剩余 100k / 每轮 40k = 2.5 轮
        assertThat(m.budgetRemainingTokens()).isEqualTo(100_000);
        assertThat(m.projectedRemainingTurns()).isEqualTo(2.5);
    }

    @Test
    void projectedRemainingTurns_infinite_whenNothingSentYet() {
        ContextMetrics m = metrics(0, 0, 0, 0, 0, 1_000_000, null);

        assertThat(m.projectedRemainingTurns()).isEqualTo(Double.MAX_VALUE);
    }

    /**
     * 水位只有 40%（离 Snip 的 65% 还很远），但预算已经撑不了几轮。
     */
    @Test
    void lowWaterLevel_canStillMeanTightBudget() {
        ContextMetrics m = metrics(40_000, 0, 0, 0, 900_000, 1_000_000, null);

        assertThat(m.waterLevel()).isLessThan(0.5);
        assertThat(m.projectedRemainingTurns()).isLessThan(3.0);
    }

    /**
     * 固定开销会降低预算投影：同样的上下文大小，
     * 算上工具 schema 与输出预留后，能跑的轮数更少。
     */
    @Test
    void fixedOverhead_reducesProjectedTurns() {
        ContextMetrics withoutOverhead = metrics(40_000, 0, 0, 0, 600_000, 1_000_000, null);
        ContextMetrics withOverhead = metrics(40_000, 0, 8_000, 2_000, 600_000, 1_000_000, null);

        assertThat(withoutOverhead.projectedRemainingTurns()).isEqualTo(10.0);
        assertThat(withOverhead.projectedRemainingTurns()).isEqualTo(8.0);
    }

    // ═══════════════════ 构成分解 ═══════════════════

    @Test
    void composition_reportsDominantKindFirst() {
        ContextMetrics m = metrics(0, 0, 0, 0, 0, 0,
                Map.of(
                        BlockKind.TOOL_ARGS, 7_200L,
                        BlockKind.TOOL_RESULT, 2_600L,
                        BlockKind.AI_TEXT, 200L));

        assertThat(m.composition()).isEqualTo("TOOL_ARGS 72% | TOOL_RESULT 26% | AI_TEXT 2%");
    }

    @Test
    void composition_handlesEmptyWindow() {
        assertThat(simple(0).composition()).isEqualTo("(空)");
    }
}
