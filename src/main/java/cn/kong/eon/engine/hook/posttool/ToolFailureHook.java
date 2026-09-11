package cn.kong.eon.engine.hook.posttool;

import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 失败熔断（PostTool, order=30）。检测单工具连续失败，
 * 熔断后工具被执行层拦截跳过，冷却结束后自动恢复。
 */
@Component
public class ToolFailureHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(ToolFailureHook.class);

    @Override
    public String name() {
        return "ToolFailureHook";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public HookResult afterToolExecution(RunContext r, String toolName, boolean success) {
        String msg = r.session().circuitBreaker().record(toolName, success);
        if (!msg.isEmpty()) {
            log.warn("[ToolFailureHook] {}", msg);
            r.task().addNudge(msg);
        }
        return HookResult.ok();
    }
}
