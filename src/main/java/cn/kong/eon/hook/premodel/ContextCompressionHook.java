package cn.kong.eon.hook.premodel;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.context.ContextMetrics;
import cn.kong.eon.context.ContextWindow;
import cn.kong.eon.context.block.CompressionLevel;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.stop.StopCategory;
import cn.kong.eon.context.CompressionState;
import cn.kong.eon.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 压缩执行点（PreModel, order=100）。每轮调 LLM 前调用
 * {@link CompressionPolicy#apply} 判定并执行一个压缩档位。
 */
public class ContextCompressionHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionHook.class);

    private final CompressionPolicy policy;

    public ContextCompressionHook(CompressionPolicy policy) {
        this.policy = policy;
    }

    @Override
    public String name() {
        return "ContextCompressionHook";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        ContextWindow window = ctx.getWindow();
        if (window == null || window.isEmpty()) {
            return HookResult.stop(StopCategory.UNEXPECTED_ERROR,
                    StopCategory.UNEXPECTED_ERROR.format("上下文为空"));
        }

        CompressionState cs = state.getCompressionState();
        ContextMetrics before = ctx.metrics();

        CompressionLevel level = policy.apply(window, before, cs, state.getTurnCount());
        if (!level.enabled()) {
            return HookResult.ok();
        }

        ctx.setSummary(cs.getLastSummary());

        // 处置后窗口变了，度量要重算
        log.info("[ContextCompressionHook] {}: 水位 {} -> {}", level, pct(before.waterLevel()), pct(ctx.metrics().waterLevel()));

        return HookResult.ok();
    }

    private static String pct(double ratio) {
        return String.format("%.0f%%", ratio * 100);
    }
}
