package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时提醒渲染（模型调用前阶段，order=10）。
 * 把 pendingNudges 和 formatCorrections 渲染到 ContextBuilder 的 runtimeNudges 字段。
 */
public class NudgeRenderHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(NudgeRenderHook.class);

    @Override public String name() { return "NudgeRender"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 10; }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        if (state.getPendingNudges().isEmpty() && state.getFormatCorrections().isEmpty()) {
            return HookResult.ok();
        }

        StringBuilder sb = new StringBuilder("## [Runtime] 运行时提醒（本轮有效）\n");
        for (String nudge : state.getPendingNudges()) {
            sb.append("- ").append(nudge).append("\n");
        }
        for (String correction : state.getFormatCorrections()) {
            sb.append("- ").append(correction).append("\n");
        }

        ctx.setRuntimeNudges(sb.toString());
        log.debug("Runtime nudges rendered: {} items",
                state.getPendingNudges().size() + state.getFormatCorrections().size());

        return HookResult.ok();
    }
}
