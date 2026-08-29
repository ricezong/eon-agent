package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.Retention;
import cn.kong.eon.model.CompressionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 预算感知规则测试。
 * <p>
 * 这组测试钉死本次改造的核心判断：<b>无损处置看预算投影，有损压缩看水位</b>。
 * <p>
 * 用户的质疑是"上下文还有大量空间，没必要压缩"——这个判断对<b>有损</b>操作成立，
 * 对<b>无损</b>操作不成立：水位是瞬时大小，预算是大小对时间的积分。
 * 水位只有 40% 但每轮成本高时，预算会先于水位耗尽；
 * 此时把磁盘上已有副本的内容换成一行引用，是零信息损失，且越早做省得越多。
 */
class BudgetAwareRuleTest {

    private static final long CONTEXT_MAX = 100_000L;
    private static final long BUDGET_MAX = 1_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    /** write_file 的真实参数形状：路径 + 大段待写入内容 */
    private static String bigWriteArgs() {
        return "{\"file_path\":\"out/report.html\",\"contents\":\"" + "X".repeat(3000) + "\"}";
    }

    private static final ToolSupport TOOL_SUPPORT = new ToolSupport() {
        @Override
        public boolean persistsArguments(String toolName) {
            return "write_file".equals(toolName);
        }

        @Override
        public String summarizeArgs(String toolName, String argumentsJson) {
            return "{\"tool\": \"" + toolName + "\"}";
        }
    };

    /**
     * 水位低（40%，远低于 Snip 的 65%）但预算吃紧的场景：
     * 每轮发送 40k，预算只剩 100k → 还能跑 2.5 轮。
     */
    private ContextMetrics lowWaterTightBudget() {
        return ContextMetrics.builder()
                .transcriptTokens(40_000)
                .contextMaxTokens(CONTEXT_MAX)
                .budgetUsedTokens(900_000)
                .budgetMaxTokens(BUDGET_MAX)
                .build();
    }

    private ContextMetrics lowWaterAmpleBudget() {
        return ContextMetrics.builder()
                .transcriptTokens(40_000)
                .contextMaxTokens(CONTEXT_MAX)
                .budgetUsedTokens(10_000)
                .budgetMaxTokens(BUDGET_MAX)
                .build();
    }

    private static ContextBlock resultBlock(String id, String callId, String text, int turn) {
        return ContextBlock.builder()
                .id(id)
                .kind(BlockKind.TOOL_RESULT)
                .retention(Retention.COMPRESSIBLE)
                .groupId("g-" + id)
                .ordinal(0)
                .turn(turn)
                .toolName("read_file")
                .toolCallId(callId)
                .text(text)
                .build();
    }

    private static ContextBlock argsBlock(String id, String callId, String toolName, String text, int turn) {
        return ContextBlock.builder()
                .id(id)
                .kind(BlockKind.TOOL_ARGS)
                .retention(Retention.OFFLOADABLE)
                .groupId("g-" + id)
                .ordinal(0)
                .turn(turn)
                .toolName(toolName)
                .toolCallId(callId)
                .text(text)
                .build();
    }

    // ═══════════════════ 引用折叠 ═══════════════════

    @Test
    void referenceCollapse_firesOnBudgetProjection_evenWhenWaterLevelIsLow() {
        ContextWindow window = new ContextWindow();
        ContextBlock block = resultBlock("b1", "c1", "x".repeat(5000), 1);
        block.setRefId("art_001");
        window.addAll(List.of(block));

        ContextMetrics metrics = lowWaterTightBudget();
        assertThat(metrics.waterLevel()).isLessThan(0.5);          // 水位确实低
        assertThat(metrics.projectedRemainingTurns()).isLessThan(8.0); // 但预算吃紧

        ContextPolicy policy = new ContextPolicy(List.of(new ReferenceCollapseRule(8.0, 200)), 0.05);
        PolicyResult result = policy.runEligible(window, metrics, new CompressionState(), 0, 2, 5);

        assertThat(result.applied()).isTrue();
        assertThat(block.text()).contains("artifact://art_001");
        assertThat(block.chars()).isLessThan(100);
    }

    /**
     * 同一水位下，有损规则必须保持不动 —— 这是两条路径的分水岭。
     */
    @Test
    void lossySnip_doesNotFire_atSameLowWaterLevel() {
        ContextWindow window = new ContextWindow();
        ContextBlock block = resultBlock("b1", "c1", "x".repeat(5000), 1);
        window.addAll(List.of(block));

        ContextPolicy policy = new ContextPolicy(List.of(new SnipRule(0.65, 100, 2000)), 0.05);
        PolicyResult result = policy.runEligible(window, lowWaterTightBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(result.applied()).isFalse();
        assertThat(block.chars()).isEqualTo(5000);
    }

    @Test
    void referenceCollapse_onlyTouchesBlocksWithDiskCopy() {
        ContextWindow window = new ContextWindow();
        ContextBlock noRef = resultBlock("b1", "c1", "x".repeat(5000), 1);
        window.addAll(List.of(noRef));

        ContextPolicy policy = new ContextPolicy(List.of(new ReferenceCollapseRule(8.0, 200)), 0.05);
        PolicyResult result = policy.runEligible(window, lowWaterTightBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(result.applied()).isFalse();
        assertThat(noRef.chars()).isEqualTo(5000);
    }

    @Test
    void referenceCollapse_skipsWhenBudgetIsAmple() {
        ContextWindow window = new ContextWindow();
        ContextBlock block = resultBlock("b1", "c1", "x".repeat(5000), 1);
        block.setRefId("art_001");
        window.addAll(List.of(block));

        ContextPolicy policy = new ContextPolicy(List.of(new ReferenceCollapseRule(8.0, 200)), 0.05);
        PolicyResult result = policy.runEligible(window, lowWaterAmpleBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(result.applied()).isFalse();
    }

    // ═══════════════════ 预算感知的参数卸载 ═══════════════════

    /**
     * 覆盖入站管线拿不到信息的场景（从 transcript 恢复的历史会话）：
     * 入站时没有"调用是否成功"的上下文，只能留到策略阶段看到结果块后补判。
     */
    @Test
    void budgetAwareOffload_offloadsArgs_whenResultBlockShowsSuccess() {
        ContextWindow window = new ContextWindow();
        ContextBlock args = argsBlock("a1", "c1", "write_file", bigWriteArgs(), 1);
        ContextBlock result = resultBlock("r1", "c1", "ok", 1);
        result.setSuccess(true);
        window.addAll(List.of(args, result));

        ContextPolicy policy = new ContextPolicy(
                List.of(new BudgetAwareOffloadRule(8.0, 500, TOOL_SUPPORT, mapper)), 0.05);
        PolicyResult outcome = policy.runEligible(window, lowWaterTightBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(outcome.applied()).isTrue();
        assertThat(args.isOffloaded()).isTrue();
        assertThat(args.chars()).isLessThan(200);
        assertThat(args.text()).contains("已卸载");
    }

    @Test
    void budgetAwareOffload_skipsFailedCalls() {
        ContextWindow window = new ContextWindow();
        ContextBlock args = argsBlock("a1", "c1", "write_file", bigWriteArgs(), 1);
        ContextBlock result = resultBlock("r1", "c1", "boom", 1);
        result.setSuccess(false);
        window.addAll(List.of(args, result));

        ContextPolicy policy = new ContextPolicy(
                List.of(new BudgetAwareOffloadRule(8.0, 500, TOOL_SUPPORT, mapper)), 0.05);
        PolicyResult outcome = policy.runEligible(window, lowWaterTightBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(outcome.applied()).isFalse();
        assertThat(args.isOffloaded()).isFalse();
    }

    @Test
    void budgetAwareOffload_protectsCurrentTurn() {
        ContextWindow window = new ContextWindow();
        // 参数块就在当前轮，模型可能马上要引用它
        ContextBlock args = argsBlock("a1", "c1", "write_file", bigWriteArgs(), 5);
        ContextBlock result = resultBlock("r1", "c1", "ok", 5);
        result.setSuccess(true);
        window.addAll(List.of(args, result));

        ContextPolicy policy = new ContextPolicy(
                List.of(new BudgetAwareOffloadRule(8.0, 500, TOOL_SUPPORT, mapper)), 0.05);
        PolicyResult outcome = policy.runEligible(window, lowWaterTightBudget(),
                new CompressionState(), 0, 2, 5);

        assertThat(outcome.applied()).isFalse();
    }

    /**
     * 回归：策略阶段的卸载与入站阶段共用同一份实现，
     * 输出同样必须是严格合法 JSON——历史上这里曾产出
     * {@code {path: "x"} /* 注释 *\/}（键名无引号 + JSON 外注释），直接导致 400。
     */
    @Test
    void budgetAwareOffload_producesStrictlyValidJson() {
        ContextWindow window = new ContextWindow();
        ContextBlock args = argsBlock("a1", "c1", "write_file", bigWriteArgs(), 1);
        ContextBlock result = resultBlock("r1", "c1", "ok", 1);
        result.setSuccess(true);
        window.addAll(List.of(args, result));

        ContextPolicy policy = new ContextPolicy(
                List.of(new BudgetAwareOffloadRule(8.0, 500, TOOL_SUPPORT, mapper)), 0.05);
        policy.runEligible(window, lowWaterTightBudget(), new CompressionState(), 0, 2, 5);

        assertThat(args.isOffloaded()).isTrue();
        assertThatCode(() -> mapper.readValue(args.text(), Map.class))
                .as("策略阶段卸载后的 arguments 必须是严格合法 JSON，不得带注释")
                .doesNotThrowAnyException();
    }
}
