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
 * 预算守卫能力模块。
 *
 * <p>属于 {@link Layer#GUARD} 守卫层，orderInLayer=10（GUARD 层内最先执行）。
 * 始终激活。在 {@link #beforeModelCall} 中检查累计 token 是否超预算。</p>
 *
 * <h3>两级阈值设计</h3>
 * <ul>
 *   <li>软阈值（soft_threshold，默认 75%）：注入 nudge 提示 LLM 收尾，不中断。
 *       EonAgent 收到 nudge 后给 grace_steps 轮额外时间让 LLM 完成收尾。</li>
 *   <li>硬阈值（hard_threshold，默认 100%）：返回 {@link CapabilityResult#abort}
 *       中止循环（原设计为抛 HardBudgetExceededException，重构后改为返回值）。</li>
 * </ul>
 *
 * <p>预算独立于上下文窗口大小配置（budget.max_tokens），不再复用 context.max_tokens。</p>
 *
 * <h3>重构说明</h3>
 * <ul>
 *   <li>priority() → layer()=GUARD + orderInLayer()=10。</li>
 *   <li>beforeModelCall 返回值 void → CapabilityResult。</li>
 *   <li>删除 HardBudgetExceededException 内部类，改为返回 abort("BUDGET_EXCEEDED", reason)。</li>
 * </ul>
 */
public class BudgetGuardCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(BudgetGuardCapability.class);

    /** 中断类别：预算硬超限。 */
    public static final String CATEGORY_BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    private final AgentConfig config;
    private boolean softTriggered = false;

    public BudgetGuardCapability(AgentConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "BudgetGuard"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public Layer layer() { return Layer.GUARD; }

    @Override
    public int orderInLayer() { return 10; }

    @Override
    public CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        AgentConfig.BudgetConfig budget = config.getBudget();
        long used = state.getUsageAccum().getTotalTokens();
        long maxBudget = budget.getMaxTokens();
        double ratio = maxBudget > 0 ? (double) used / maxBudget : 0.0;

        // 硬阈值：累计 token 超过预算上限，终止
        if (ratio >= budget.getHardThreshold()) {
            log.warn("Budget HARD exceeded: {} / {} ({:.1f}%)", used, maxBudget, ratio * 100);
            return CapabilityResult.abort(
                    CATEGORY_BUDGET_EXCEEDED,
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

        return CapabilityResult.ok();
    }

    /**
     * 重置软阈值触发状态（新会话时调用）。
     */
    public void reset() {
        this.softTriggered = false;
    }
}
