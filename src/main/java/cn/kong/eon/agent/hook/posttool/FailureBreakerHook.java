package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.AbortCategory;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 失败熔断（工具执行后阶段，order=30）。
 * 检测连续失败，触发熔断。
 */
public class FailureBreakerHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(FailureBreakerHook.class);

    private final LoopDetector loopDetector;

    public FailureBreakerHook(LoopDetector loopDetector) {
        this.loopDetector = loopDetector;
    }

    @Override public String name() { return "FailureBreaker"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 30; }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        LoopDetector.DetectionResult dr = loopDetector.recordToolResult(toolName, success);
        if (dr.shouldStop()) {
            log.warn("Loop detected (afterToolExecution): {}", dr.message());
            return HookResult.abort(AbortCategory.LOOP_DETECTED, dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
        return HookResult.ok();
    }
}
