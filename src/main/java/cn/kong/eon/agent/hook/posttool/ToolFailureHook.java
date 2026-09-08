package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.exec.ToolBreaker;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 失败熔断（PostTool, order=30）。检测单工具连续失败：
 * warn 阈值时注入 nudge；熔断时也注入 nudge，不终止会话。
 * 熔断后工具被执行层拦截跳过，冷却结束后自动恢复。
 */
public class ToolFailureHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(ToolFailureHook.class);

    private final ToolBreaker breaker;

    public ToolFailureHook(ToolBreaker breaker) {
        this.breaker = breaker;
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
        String msg = breaker.record(toolName, success);
        if (!msg.isEmpty()) {
            log.warn("[ToolFailureHook] {}", msg);
            state.addNudge(msg);
        }
        return HookResult.ok();
    }
}
