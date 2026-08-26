package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 失败熔断（PostTool, order=30）。检测单工具连续失败，注入 nudge 提示，不触发会话级停止。 */
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
            // 单工具熔断：注入 nudge 提示 LLM 不要再调用此工具，但不触发会话级优雅停止
            log.warn("[PostTool] FailureBreaker: tool '{}' tripped - {}", toolName, dr.message());
            state.getPendingNudges().add(dr.message());
            return HookResult.ok();
        }
        if (dr.shouldWarn()) {
            log.info("[PostTool] FailureBreaker: WARN - {}", dr.message());
            state.getPendingNudges().add(dr.message());
        }
        return HookResult.ok();
    }
}
