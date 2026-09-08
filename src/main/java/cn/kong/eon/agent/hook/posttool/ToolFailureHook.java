package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.exec.ToolHealthTracker;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 失败熔断（PostTool, order=30）。检测单工具连续失败：
 * 熔断后工具被执行层拦截跳过，冷却结束后自动恢复。
 */
public class ToolFailureHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(ToolFailureHook.class);

    private final ToolHealthTracker tracker;

    public ToolFailureHook(ToolHealthTracker tracker) {
        this.tracker = tracker;
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
        String msg = tracker.record(toolName, success);
        if (!msg.isEmpty()) {
            log.warn("[ToolFailureHook] {}", msg);
            state.addNudge(msg);
        }
        return HookResult.ok();
    }
}
