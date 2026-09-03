package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.block.CompressionLevel;
import cn.kong.eon.model.CompressionState;

/**
 * 压缩机制的策略编排者。每轮在 PreModel 阶段执行一次，流程固定为四步：
 * 判档位 → 处置 → 修复配对 → 记录结果。
 * <p>
 * SUMMARIZE 档的顺序是"先摘要、后删除"：摘要生成成功写入状态之后才移除原文，
 * 否则一旦摘要失败就会同时失去原文与摘要。
 */
public class CompressionPolicy {

    private final CompressionSettings settings;
    private final BlockDisposer disposer;
    private final ContextSummarizer summarizer;

    public CompressionPolicy(CompressionSettings settings,
                             BlockDisposer disposer,
                             ContextSummarizer summarizer) {
        this.settings = settings;
        this.disposer = disposer;
        this.summarizer = summarizer;
    }

    /**
     * 执行本轮压缩。
     *
     * @param window    上下文窗口，就地修改
     * @param metrics   当前度量，用于判定档位
     * @param state     压缩状态，摘要存放处
     * @param turnCount 当前轮次序号，用于判定轮数入口
     * @return 本轮结果；未命中档位或无块可处置时 {@link CompressionResult#applied()} 为 false
     */
    public CompressionResult apply(ContextWindow window, ContextMetrics metrics, CompressionState state, int turnCount) {

        // 判断压缩档位
        CompressionLevel level = CompressionTrigger.resolve(metrics, turnCount, settings);
        if (!level.enabled()) {
            return CompressionResult.none(level);
        }

        long charsBefore = window.totalChars();
        int cutoffTurn = window.cutoffTurn(settings.tailGuardTurns());

        int replaced = 0;
        int removed = 0;
        if (level == CompressionLevel.SUMMARIZE) {
            String summary = summarizer.summarize(window, cutoffTurn, state.getLastSummary());
            if (summary == null) {
                return CompressionResult.none(level);
            }
            state.setLastSummary(summary);
            removed = window.removeBefore(cutoffTurn).size();
            state.setSummarizedMessageCount(state.getSummarizedMessageCount() + removed);
        } else {
            replaced = disposer.dispose(window, level, cutoffTurn);
        }

        if (replaced + removed == 0) {
            return CompressionResult.none(level);
        }

        // 删除块会切断 tool_use / tool_result 配对，由窗口自动修复
        window.repairPairing();
        state.setLastTurnCompressed(turnCount);

        return new CompressionResult(level, replaced, removed, charsBefore, window.totalChars());
    }

    public CompressionSettings settings() {
        return settings;
    }
}
