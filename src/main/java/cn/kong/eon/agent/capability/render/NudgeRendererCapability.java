package cn.kong.eon.agent.capability.render;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时提醒渲染器。
 *
 * <p>属于 {@link Layer#RENDER} 渲染层，orderInLayer=10（RENDER 层内最先执行）。
 * 始终激活。在 {@link #beforeModelCall} 中把 pendingNudges 和 formatCorrections
 * 渲染到 ContextBuilder 的 runtimeNudges 字段，由 build() 统一注入为独立消息。</p>
 *
 * <h3>设计原因</h3>
 * <p>nudge 的产生者（BudgetGuard、LoopGuard 等）只管往 state.pendingNudges 写入，
 * 不关心如何渲染到上下文。TodoNavigator 只在 todo_write 调用后激活，
 * 不应承担 nudge 的渲染职责。此模块解耦 nudge 的生产与消费。</p>
 *
 * <h3>排序保证</h3>
 * <p>orderInLayer=10 保证 NudgeRenderer 在 TodoNavigator(orderInLayer=20) 之前执行。
 * 跨层排序保证 BudgetGuard(GUARD) 必先于 NudgeRenderer(RENDER) 执行——
 * 即 BudgetGuard 先产生 nudge，NudgeRenderer 再渲染 nudge。</p>
 *
 * <h3>重构说明</h3>
 * <ul>
 *   <li>priority()=HIGH → layer()=RENDER + orderInLayer()=10。</li>
 *   <li>beforeModelCall 返回值 void → CapabilityResult（始终返回 ok，不中断）。</li>
 * </ul>
 */
public class NudgeRendererCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(NudgeRendererCapability.class);

    @Override
    public String name() { return "NudgeRenderer"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public Layer layer() { return Layer.RENDER; }

    @Override
    public int orderInLayer() { return 10; }

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
