package cn.kong.eon.agent.capability.render;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时提醒渲染器（RENDER 层，order=10）。
 * 把 pendingNudges 和 formatCorrections 渲染到 ContextBuilder 的 runtimeNudges 字段。
 */
public class NudgeRendererCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(NudgeRendererCapability.class);

    @Override public String name() { return "NudgeRenderer"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public Layer layer() { return Layer.RENDER; }
    @Override public int orderInLayer() { return 10; }

    @Override
    public CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        if (state.getPendingNudges().isEmpty() && state.getFormatCorrections().isEmpty()) {
            return CapabilityResult.ok();
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

        return CapabilityResult.ok();
    }
}
