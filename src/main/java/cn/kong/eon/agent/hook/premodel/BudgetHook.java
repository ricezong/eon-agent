package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预算检查（PreModel, order=10）。达到阈值比例注入收尾提示词，预算耗尽则终止。
 */
public class BudgetHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(BudgetHook.class);

    private final AgentConfig config;

    public BudgetHook(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Budget";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = state.getUsageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = (double) used / maxBudget;

        // 预算耗尽，终止
        if (used >= maxBudget) {
            log.warn("[预算] 超限 {}% ({}/{}) → 停止", String.format("%.0f", ratio * 100), used, maxBudget);
            StopReason reason = new StopReason(
                    StopCategory.BUDGET_EXCEEDED,
                    "Token 预算超限: " + used + " >= " + maxBudget);
            return HookResult.stop(reason);
        }

        // 达到阈值比例，注入收尾提示词
        if (ratio >= budget.getThreshold()) {
            int remainingSteps = config.getLoop().getMaxSteps() - state.getTurnCount();
            String nudge = String.format(
                    "⚠️ 预算告警：累计已消耗 %d token（预算上限 %d，已用 %.0f%%）。"
                            + "剩余约 %d 轮，请尽快用已有信息整理总结并直接回复用户，"
                            + "不要再发起新的工具调用。",
                    used, maxBudget, ratio * 100, Math.max(remainingSteps, 0));
            state.addNudge(nudge);
            log.info("[预算] 告警 {}% ({}/{})", String.format("%.0f", ratio * 100), used, maxBudget);
        }

        return HookResult.ok();
    }
}
