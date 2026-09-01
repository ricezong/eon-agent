package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.policy.ContextPolicy;
import cn.kong.eon.agent.context.policy.PolicyResult;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文策略执行点（PreModel, order=100）。
 * 调用 {@link ContextPolicy#runEligible} 执行满足触发条件的压缩规则。
 */
public class ContextCompactHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactHook.class);

    private final AgentConfig config;
    private final ContextPolicy policy;

    public ContextCompactHook(AgentConfig config, ContextPolicy policy) {
        this.config = config;
        this.policy = policy;
    }

    @Override
    public String name() {
        return "ContextCompact";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        ContextWindow window = ctx.getWindow();
        if (window == null || window.isEmpty()) {
            return HookResult.ok();
        }

        CompressionState cs = state.getCompressionState();
        // 尾部保护区
        int tailGuardTurns = config.getContext().getTailGuardMinTurns();
        // 距离上次压缩后又执行了几轮
        int turnsSinceLastCompress = state.getTurnCount() - cs.getLastTurnCompressed();
        int blocksBefore = window.size();

        PolicyResult result = policy.runEligible(
                window,
                ctx.metrics(state),
                cs,
                turnsSinceLastCompress,
                tailGuardTurns,
                state.getTurnCount()
        );

        // 处置后窗口变了，度量要重算
        var metricsAfter = ctx.metrics(state);
        cs.setLastWaterLevel(metricsAfter.waterLevel());

        if (!result.applied()) {
            return HookResult.ok();
        }

        // 删除块会切断 tool_use / tool_result 配对，由窗口自动修复
        window.repairPairing();
        cs.setLastTurnCompressed(state.getTurnCount());

        log.info("[上下文] {} | {} -> {} 块 | {} -> {} 字符 (降幅 {}) | 水位 {} | 投影剩余 {} 轮 | 构成 {}",
                result.describe(),
                blocksBefore, window.size(),
                result.charsBefore(), result.charsAfter(), pct(result.reduction()),
                pct(metricsAfter.waterLevel()),
                String.format("%.1f", metricsAfter.projectedRemainingTurns()),
                metricsAfter.composition());

        return HookResult.ok();
    }

    private static String pct(double ratio) {
        return String.format("%.0f%%", ratio * 100);
    }
}
