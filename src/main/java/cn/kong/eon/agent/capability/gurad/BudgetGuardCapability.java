package cn.kong.eon.agent.capability.gurad;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预算守卫能力模块（GUARD 层，order=10）。
 * 软阈值注入 nudge 提示收尾；硬阈值中断循环。
 */
public class BudgetGuardCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(BudgetGuardCapability.class);

    public static final String CATEGORY_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    private final AgentConfig config;
    private boolean softTriggered = false;

    public BudgetGuardCapability(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "BudgetGuard"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public Layer layer() { return Layer.GUARD; }
    @Override public int orderInLayer() { return 10; }

    @Override
    public CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = state.getUsageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = maxBudget > 0 ? (double) used / maxBudget : 0.0;

        if (ratio >= budget.getHardThreshold()) {
            log.warn("Budget HARD exceeded: {} / {} ({:.1f}%)", used, maxBudget, ratio * 100);
            return CapabilityResult.abort(
                    CATEGORY_BUDGET_EXCEEDED,
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

        return CapabilityResult.ok();
    }

    public void reset() {
        this.softTriggered = false;
    }
}
