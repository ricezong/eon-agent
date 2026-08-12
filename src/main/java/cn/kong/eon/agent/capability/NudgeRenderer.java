package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时提醒渲染器。
 *
 * 始终激活。在 beforeModelCall 中把 pendingNudges 和 formatCorrections
 * 渲染到 ContextBuilder 的 runtimeNudges 字段，由 build() 统一注入为独立消息。
 *
 * 设计原因：
 * nudge 的产生者（BudgetGuard、LoopGuard 等）只管往 state.pendingNudges 写入，
 * 不关心如何渲染到上下文。TodoNavigator 只在 todo_write 调用后激活，
 * 不应承担 nudge 的渲染职责。此模块解耦 nudge 的生产与消费。
 */
public class NudgeRenderer implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(NudgeRenderer.class);

    @Override
    public String name() { return "NudgeRenderer"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public int priority() { return Priority.HIGH; }

    @Override
    public void beforeModelCall(SessionState state, ContextBuilder ctx) {
        if (state.getPendingNudges().isEmpty() && state.getFormatCorrections().isEmpty()) {
            return;
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
    }
}
