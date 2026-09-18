package cn.kong.eon.engine.hook.premodel;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.runtime.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预算检查（PreModel, order=10）。达到阈值比例注入收尾提示词，预算耗尽则终止。
 */
@Component
public class BudgetHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(BudgetHook.class);

    /** 预算告警 nudge 模板：%1$d=已用 token，%2$d=预算上限，%3$.0f=已用百分比，%4$d=剩余轮数 */
    private static final String BUDGET_WARN_NUDGE = """
            ⚠️ 预算告警：累计已消耗 %1$d token（预算上限 %2$d，已用 %3$.0f%%）。
            剩余约 %4$d 轮，请尽快用已有信息整理总结并直接回复用户，不要再发起新的工具调用。
            """;

    private final AgentConfig config;

    public BudgetHook(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Budget";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public HookResult beforeModelCall(RunContext r) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = r.session().usageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = (double) used / maxBudget;

        // 预算耗尽，终止
        if (used >= maxBudget) {
            log.warn("[Budget] 超限 {}% ({}/{}) → 停止", String.format("%.0f", ratio * 100), used, maxBudget);
            return HookResult.stop(StopCategory.BUDGET_EXCEEDED, StopCategory.BUDGET_EXCEEDED.format(used, maxBudget));
        }

        // 达到阈值比例，注入收尾提示词
        if (ratio >= budget.getThreshold()) {
            int remainingSteps = config.getLoop().getMaxSteps() - r.task().turnCount();
            String nudge = String.format(BUDGET_WARN_NUDGE, used, maxBudget, ratio * 100, Math.max(remainingSteps, 0));
            r.task().addNudge(nudge);
            log.info("[Budget] 告警 {}% ({}/{})", String.format("%.0f", ratio * 100), used, maxBudget);
        }

        return HookResult.ok();
    }
}
