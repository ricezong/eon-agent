package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 预算检查（PreModel, order=10）。软阈值注入收尾 nudge，硬阈值请求优雅停止。 */
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
            log.warn("[PreModel] Budget: HARD exceeded {}% ({}/{}) → STOP",
                    String.format("%.0f", ratio * 100), used, maxBudget);
            // 请求优雅停止，给 LLM graceSteps 轮整理输出
            StopReason reason = new StopReason(
                    StopCategory.BUDGET_EXCEEDED,
                    "Token 预算硬超限: " + used + " >= " + (long)(maxBudget * budget.getHardThreshold()),
                    budget.getGraceSteps());
            return HookResult.stop(reason);
        }

        if (ratio >= budget.getSoftThreshold() && !softTriggered) {
            softTriggered = true;
            int remainingSteps = budget.getGraceSteps();
            String nudge = String.format(
                    "⚠️ 预算告警：累计已消耗 %d token（预算上限 %d，已用 %.0f%%）。"
                            + "剩余约 %d 轮，请尽快用已有信息整理总结并直接回复用户，"
                            + "不要再发起新的工具调用。",
                    used, maxBudget, ratio * 100, remainingSteps);
            state.addNudge(nudge);
            log.info("[PreModel] Budget: SOFT {}% ({}/{})", String.format("%.0f", ratio * 100), used, maxBudget);
        }

        return HookResult.ok();
    }
}
