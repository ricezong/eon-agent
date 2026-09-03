package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.policy.CompressionPolicy;
import cn.kong.eon.agent.context.policy.CompressionResult;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 压缩执行点（PreModel, order=100）。每轮调 LLM 前调用
 * {@link CompressionPolicy#apply} 判定并执行一个压缩档位。
 */
public class ContextCompactHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactHook.class);

    private final CompressionPolicy policy;

    public ContextCompactHook(CompressionPolicy policy) {
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
            return HookResult.stop(new StopReason(StopCategory.UNEXPECTED_ERROR, "上下文为空"));
        }

        CompressionState cs = state.getCompressionState();
        int blocksBefore = window.size();

        CompressionResult result = policy.apply(window, ctx.metrics(state), cs, state.getTurnCount());

        if (!result.applied()) {
            return HookResult.ok();
        }

        ctx.setSummary(cs.getLastSummary());

        // 处置后窗口变了，度量要重算
        var metricsAfter = ctx.metrics(state);
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
