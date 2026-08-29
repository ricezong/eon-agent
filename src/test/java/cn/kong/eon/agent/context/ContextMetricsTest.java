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

    private ContextMetrics.Builder base() {
        return ContextMetrics.builder().contextMaxTokens(CONTEXT_MAX);
    }

    @Test
    void waterLevel_includesFixedPerTurnOverhead() {
        ContextMetrics m = base()
                .transcriptTokens(50_000)
                .anchorTokens(2_000)
                .toolSchemaTokens(5_000)
                .outputReserveTokens(3_000)
                .build();

        // 50k + 2k + 5k + 3k = 60k / 100k
        assertThat(m.sentTokens()).isEqualTo(60_000);
        assertThat(m.waterLevel()).isEqualTo(0.6);
    }

    @Test
    void waterLevel_ignoresFixedOverhead_whenNotProvided() {
        ContextMetrics m = base().transcriptTokens(50_000).build();

        assertThat(m.waterLevel()).isEqualTo(0.5);
        assertThat(m.transcriptRatio()).isEqualTo(0.5);
    }

    @Test
    void waterLevel_cappedAtOne() {
        ContextMetrics m = base().transcriptTokens(200_000).build();

        assertThat(m.waterLevel()).isEqualTo(1.0);
    }

    // ═══════════════════ 预算投影 ═══════════════════

    @Test
    void projectedRemainingTurns_dividesRemainingBudgetByPerTurnCost() {
        ContextMetrics m = base()
                .transcriptTokens(40_000)
                .budgetUsedTokens(900_000)
                .budgetMaxTokens(1_000_000)
                .build();

        // 剩余 100k / 每轮 40k = 2.5 轮
        assertThat(m.budgetRemainingTokens()).isEqualTo(100_000);
        assertThat(m.projectedRemainingTurns()).isEqualTo(2.5);
    }

    @Test
    void projectedRemainingTurns_infinite_whenNothingSentYet() {
        ContextMetrics m = base().budgetMaxTokens(1_000_000).build();

        assertThat(m.projectedRemainingTurns()).isEqualTo(Double.MAX_VALUE);
    }

    /**
     * 这条测试就是"预算先于水位耗尽"的量化表达：
     * 水位只有 40%（离 Snip 的 65% 还很远），但预算已经撑不了几轮。
     * 只看水位会一直等下去，等到水位涨上来，预算早就没了。
     */
    @Test
    void lowWaterLevel_canStillMeanTightBudget() {
        ContextMetrics m = base()
                .transcriptTokens(40_000)
                .budgetUsedTokens(900_000)
                .budgetMaxTokens(1_000_000)
                .build();

        assertThat(m.waterLevel()).isLessThan(0.5);
        assertThat(m.projectedRemainingTurns()).isLessThan(3.0);
    }

    /**
     * 固定开销会降低预算投影：同样的上下文大小，
     * 算上工具 schema 与输出预留后，能跑的轮数更少。
     */
    @Test
    void fixedOverhead_reducesProjectedTurns() {
        ContextMetrics withoutOverhead = base()
                .transcriptTokens(40_000)
                .budgetUsedTokens(600_000)
                .budgetMaxTokens(1_000_000)
                .build();

        ContextMetrics withOverhead = base()
                .transcriptTokens(40_000)
                .toolSchemaTokens(8_000)
                .outputReserveTokens(2_000)
                .budgetUsedTokens(600_000)
                .budgetMaxTokens(1_000_000)
                .build();

        assertThat(withoutOverhead.projectedRemainingTurns()).isEqualTo(10.0);
        assertThat(withOverhead.projectedRemainingTurns()).isEqualTo(8.0);
    }

    // ═══════════════════ 构成分解 ═══════════════════

    @Test
    void composition_reportsDominantKindFirst() {
        ContextMetrics m = base()
                .tokensByKind(Map.of(
                        BlockKind.TOOL_ARGS, 7_200L,
                        BlockKind.TOOL_RESULT, 2_600L,
                        BlockKind.AI_TEXT, 200L))
                .build();

        assertThat(m.composition()).isEqualTo("TOOL_ARGS 72% | TOOL_RESULT 26% | AI_TEXT 2%");
    }

    @Test
    void composition_handlesEmptyWindow() {
        assertThat(base().build().composition()).isEqualTo("(空)");
    }

    @Test
    void toString_isDiagnostic() {
        ContextMetrics m = base()
                .transcriptTokens(40_000)
                .budgetUsedTokens(900_000)
                .budgetMaxTokens(1_000_000)
                .tokensByKind(Map.of(BlockKind.TOOL_ARGS, 100L))
                .build();

        assertThat(m.toString())
                .contains("水位=40.0%")
                .contains("预算=90.0%")
                .contains("投影剩余=2.5轮")
                .contains("TOOL_ARGS");
    }
}
