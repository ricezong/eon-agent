package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 截断规则。对超长的可压缩块做头尾保留截断（Snip）。
 */
public class SnipRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(SnipRule.class);

    private final double waterThreshold;
    private final int summarizeTurns;
    private final int snipKeepChars;

    public SnipRule(double waterThreshold, int summarizeTurns, int snipKeepChars) {
        this.waterThreshold = waterThreshold;
        this.summarizeTurns = summarizeTurns;
        this.snipKeepChars = snipKeepChars;
    }

    @Override
    public String name() {
        return "Snip";
    }

    @Override
    public boolean shouldFire(ContextMetrics metrics, int turnsSinceLastCompress) {
        return metrics.waterLevel() >= waterThreshold
                || turnsSinceLastCompress >= summarizeTurns;
    }

    @Override
    public PolicyResult apply(RuleContext ctx) {
        long before = ctx.window().totalChars();
        int count = 0;

        for (ContextBlock block : ctx.window().blocks()) {
            if (!eligible(block, ctx)) continue;
            if (block.chars() <= snipKeepChars) continue;

            String notice = block.refId() != null
                    ? "\n... [中间内容已省略。完整内容已保存，引用: " + block.refId() + "]"
                    : "\n... [中间内容已省略。此为截断后的摘要]";
            block.setText(TextTrimmer.headTail(block.text(), snipKeepChars) + notice);
            block.markSnipped();
            count++;
        }

        long after = ctx.window().totalChars();
        if (count > 0) {
            log.info("[压缩] Snip: 截短 {} 个工具结果 ({} -> {} 字符)", count, before, after);
        }
        return PolicyResult.of(count, before, after, "Snip×" + count);
    }

    private boolean eligible(ContextBlock block, RuleContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT
                && block.retention().compressible()
                && !block.isDisposed()
                && !block.isSnipped()
                && block.turn() < ctx.cutoffTurn();
    }
}
