package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.support.StopCategory;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 失败熔断（PostTool, order=30）。检测单工具连续失败：
 * 达到 warn 阈值时注入 nudge 提示；达到 stop 阈值时终止会话。
 */
public class ToolFailureHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(ToolFailureHook.class);

    private final LoopDetector loopDetector;

    public ToolFailureHook(LoopDetector loopDetector) {
        this.loopDetector = loopDetector;
    }

    @Override
    public String name() {
        return "ToolFailureHook";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        LoopDetector.DetectionResult dr = loopDetector.recordToolResult(toolName, success);
        if (dr.stop()) {
            log.warn("[ToolFailureHook] 工具 '{}' 已熔断 - {}", toolName, dr.message());
            return HookResult.stop(StopCategory.FAILURE_BREAKER, StopCategory.FAILURE_BREAKER.format(dr.message()));
        }
        if (dr.warn()) {
            log.info("[ToolFailureHook] 告警 - {}", dr.message());
            state.addNudge(dr.message());
        }
        return HookResult.ok();
    }
}
