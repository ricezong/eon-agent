package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.AbortCategory;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预算检查（模型调用前阶段，order=10）。
 * 软阈值注入 nudge 提示收尾；硬阈值中断循环。
 */
public class BudgetHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(BudgetHook.class);

    private final AgentConfig config;
    private boolean softTriggered = false;

    public BudgetHook(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "Budget"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 10; }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = state.getUsageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = maxBudget > 0 ? (double) used / maxBudget : 0.0;

        if (ratio >= budget.getHardThreshold()) {
            log.warn("Budget HARD exceeded: {} / {} ({:.1f}%)", used, maxBudget, ratio * 100);
            return HookResult.abort(
                    AbortCategory.BUDGET_EXCEEDED,
                    "Token 预算硬超限: " + used + " >= " + (long)(maxBudget * budget.getHardThreshold()));
        }

        if (ratio >= budget.getSoftThreshold() && !softTriggered) {
            softTriggered = true;
            int remainingSteps = budget.getGraceSteps();
            String nudge = String.format(
                    "⚠️ 预算告警：累计已消耗 %d token（预算上限 %d，已用 %.0f%%）。"
                            + "剩余约 %d 轮，请尽快用已有信息整理总结并调用 finish 收尾，"
                            + "不要再发起新的搜索或下载。",
                    used, maxBudget, ratio * 100, remainingSteps);
            state.addNudge(nudge);
            log.info("Budget SOFT threshold reached: {} / {} ({:.1f}%), nudge injected ({} grace steps)",
                    used, maxBudget, ratio * 100, remainingSteps);
        }

        return HookResult.ok();
    }

    public void reset() {
        this.softTriggered = false;
    }
}
