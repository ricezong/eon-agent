package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prune：把旧的工具结果替换为占位符（有损，第二级）。
 * <p>
 * 仅当磁盘上<b>没有</b>副本时才真正有损；已落盘的块会保留 artifact 引用，
 * 此时降级为无损操作——这是把"是否已落盘"建模成块状态后的自然结果。
 */
public class PruneRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(PruneRule.class);

    private final double waterThreshold;
    private final int summarizeTurns;

    public PruneRule(double waterThreshold, int summarizeTurns) {
        this.waterThreshold = waterThreshold;
        this.summarizeTurns = summarizeTurns;
    }

    @Override
    public String name() {
        return "Prune";
    }

    @Override
    public boolean shouldFire(ContextMetrics metrics, int turnsSinceLastCompress) {
        return metrics.waterLevel() >= waterThreshold
                || turnsSinceLastCompress >= summarizeTurns * 2;
    }

    @Override
    public PolicyResult apply(RuleContext ctx) {
        long before = ctx.window().totalChars();
        int count = 0;

        for (ContextBlock block : ctx.window().blocks()) {
            if (!eligible(block, ctx)) continue;

            String placeholder = block.refId() != null
                    ? "[旧工具结果内容已清除。引用: " + block.refId() + "]"
                    : "[旧工具结果内容已清除]";
            block.setText(placeholder);
            block.markPruned();
            count++;
        }

        long after = ctx.window().totalChars();
        if (count > 0) {
            log.info("[压缩] Prune: 替换 {} 个工具结果为占位符", count);
        }
        return PolicyResult.of(count, before, after, "Prune×" + count);
    }

    private boolean eligible(ContextBlock block, RuleContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT
                && block.retention().compressible()
                && !block.isDisposed()
                && block.turn() < ctx.cutoffTurn();
    }
}
