package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预算守卫能力模块。
 *
 * 始终激活。在 beforeModelCall 中检查累计 token 是否超预算。
 *
 * 两级阈值设计：
 * - 软阈值（soft_threshold，默认 75%）：注入 nudge 提示 LLM 收尾，不中断。
 *   EonAgent 收到 nudge 后给 grace_steps 轮额外时间让 LLM 完成收尾。
 * - 硬阈值（hard_threshold，默认 100%）：抛 HardBudgetExceededException 终止循环。
 *
 * 预算独立于上下文窗口大小配置（budget.max_tokens），不再复用 context.max_tokens。
 */
public class BudgetGuard implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);

    private final AgentConfig config;
    private boolean softTriggered = false;

    public BudgetGuard(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "BudgetGuard"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public int priority() { return Priority.HIGH; }

    @Override
    public void beforeModelCall(SessionState state, ContextBuilder ctx) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = state.getUsageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = maxBudget > 0 ? (double) used / maxBudget : 0.0;

        // 硬阈值：累计 token 超过预算上限，终止
        if (ratio >= budget.getHardThreshold()) {
            log.warn("Budget HARD exceeded: {} / {} ({:.1f}%)", used, maxBudget, ratio * 100);
            throw new HardBudgetExceededException(
                    "Token 预算硬超限: " + used + " >= " + (long)(maxBudget * budget.getHardThreshold()));
        }

        // 软阈值：累计 token 接近预算，注入收尾提示
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
    }

    /**
     * 重置软阈值触发状态（新会话时调用）。
     */
    public void reset() {
        this.softTriggered = false;
    }

    /**
     * 硬预算超限异常：累计 token 达到硬阈值，强制终止。
     */
    public static class HardBudgetExceededException extends RuntimeException {
        public HardBudgetExceededException(String message) {
            super(message);
        }
    }
}
