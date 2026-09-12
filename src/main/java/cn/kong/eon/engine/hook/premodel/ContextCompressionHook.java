package cn.kong.eon.engine.hook.premodel;

import cn.kong.eon.context.CompressionState;
import cn.kong.eon.context.ContextMetrics;
import cn.kong.eon.context.ContextWindow;
import cn.kong.eon.context.block.CompressionLevel;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.runtime.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 压缩执行点（PreModel, order=100）。每轮调 LLM 前调用 CompressionPolicy#apply
 * 判定并执行一个压缩档位。
 */
@Component
public class ContextCompressionHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompressionHook.class);

    @Override
    public String name() {
        return "ContextCompressionHook";
    }

    @Override
    public HookResult beforeModelCall(RunContext r) {
        ContextWindow window = r.turn().prompt().getWindow();
        if (window == null || window.isEmpty()) {
            return HookResult.stop(StopCategory.UNEXPECTED_ERROR,
                    StopCategory.UNEXPECTED_ERROR.format("上下文为空"));
        }

        CompressionState cs = r.session().compressionState();
        ContextMetrics before = r.turn().prompt().metrics();

        CompressionLevel level = r.session().compressionPolicy()
                .apply(window, before, cs, r.task().turnCount(), r.session().ledgerPath());
        if (!level.enabled()) {
            return HookResult.ok();
        }

        r.turn().prompt().setSummary(cs.getLastSummary());

        // 处置后窗口变了，度量要重算
        log.info("[ContextCompressionHook] {}: 水位 {} -> {}", level, pct(before.waterLevel()),
                pct(r.turn().prompt().metrics().waterLevel()));

        return HookResult.ok();
    }

    private static String pct(double ratio) {
        return String.format("%.0f%%", ratio * 100);
    }
}
