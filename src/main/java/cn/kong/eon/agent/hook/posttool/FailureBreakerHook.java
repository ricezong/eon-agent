package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 失败熔断（PostTool, order=30）。检测连续失败，达到阈值请求优雅停止。 */
public class FailureBreakerHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(FailureBreakerHook.class);

    private static final int STOP_GRACE_STEPS = 2;

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
            log.warn("[PostTool] FailureBreaker: STOP - {}", dr.message());
            StopReason reason = new StopReason(
                    StopCategory.FAILURE_BREAKER,
                    dr.message(),
                    STOP_GRACE_STEPS);
            return HookResult.stop(reason);
        }
        if (dr.shouldWarn()) {
            log.info("[PostTool] FailureBreaker: WARN - {}", dr.message());
            state.getPendingNudges().add(dr.message());
        }
        return HookResult.ok();
    }
}
