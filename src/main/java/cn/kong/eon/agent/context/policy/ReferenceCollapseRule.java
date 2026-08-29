package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.block.ContextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 引用折叠（阶段 4 · 预算感知）。
 * <p>
 * 触发判据不是水位，而是<b>预算投影</b>：按当前单轮成本，剩余预算还够跑几轮。
 * 这是本次改造中唯一改变"何时开始处置"判据的规则——
 * 其余规则都只在上下文快满时才动手。
 * <p>
 * <b>为什么可以在水位不高时执行</b>：它只碰磁盘上已有完整副本的块
 * （{@code refId != null}），把摘要折叠成一行引用。
 * 这是<b>零信息损失</b>的操作，与 Snip/Prune 那种"丢内容换空间"有本质区别。
 * 用户的质疑（"上下文还有大量空间，没必要压缩"）针对的是有损操作，
 * 对无损操作不成立：无损处置越早做，中间每一轮省下的重复发送成本越多。
 * <p>
 * 效果上它改变的是积分结果——预算 = Σ 每轮发送量，
 * 单轮成本降下来，同样的预算能跑更多轮。
 */
public class ReferenceCollapseRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(ReferenceCollapseRule.class);

    private static final String COLLAPSED_TEMPLATE = "[旧工具结果已折叠为引用。完整内容: artifact://%s，可用 read_file 读取]";

    private final double minRemainingTurns;
    /** 低于此长度的块不值得折叠（折叠后的引用行本身就约 60 字符） */
    private final int minCharsToCollapse;

    public ReferenceCollapseRule(double minRemainingTurns, int minCharsToCollapse) {
        this.minRemainingTurns = minRemainingTurns;
        this.minCharsToCollapse = Math.max(0, minCharsToCollapse);
    }

    @Override
    public String name() {
        return "ReferenceCollapse";
    }

    @Override
    public int level() {
        return LEVEL_LOSSLESS;
    }

    @Override
    public List<Trigger> triggers() {
        return List.of(new Trigger.BudgetProjection(minRemainingTurns));
    }

    @Override
    public RuleOutcome apply(RuleContext ctx) {
        long before = ctx.window().totalChars();
        int count = 0;

        for (ContextBlock block : ctx.window().blocks()) {
            if (block.refId() == null) continue;
            if (block.chars() <= minCharsToCollapse) continue;
            if (block.isPruned()) continue;

            block.setText(String.format(COLLAPSED_TEMPLATE, block.refId()));
            block.markPruned();
            count++;
        }

        long after = ctx.window().totalChars();
        if (count > 0) {
            log.info("[预算感知] 引用折叠: {} 个已落盘块折叠为引用 ({} -> {} 字符，无损)",
                    count, before, after);
        }
        return RuleOutcome.of(count, before, after, "Collapse×" + count);
    }
}
