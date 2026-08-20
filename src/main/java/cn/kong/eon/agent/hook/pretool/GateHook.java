package cn.kong.eon.agent.hook.pretool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 门禁校验（工具执行前阶段，order=20）。
 * 检查破坏性工具调用的必要参数。校验失败时请求优雅停止，给 LLM 总结机会。
 */
public class GateHook implements Hook.PreToolHook {
    private static final Logger log = LoggerFactory.getLogger(GateHook.class);

    private static final int STOP_GRACE_STEPS = 2;

    private final ToolRegistry toolRegistry;
    private final boolean autoApprove;

    public GateHook(ToolRegistry toolRegistry, boolean autoApprove) {
        this.toolRegistry = toolRegistry;
        this.autoApprove = autoApprove;
    }

    @Override public String name() { return "Gate"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 20; }

    @Override
    public HookResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return HookResult.ok();

        for (ToolExecutionRequest req : requests) {
            if (toolRegistry.isDestructive(req.name())) {
                log.warn("[PreTool] Gate: destructive '{}' approved | args: {} | turn: {}",
                        req.name(), req.arguments(), state.getTurnCount());

                if (!autoApprove) {
                    // 生产环境可接入审批流
                }

                if (req.arguments() == null || !req.arguments().contains("url")) {
                    log.warn("[PreTool] Gate: REJECTED '{}' missing 'url' → STOP", req.name());
                    StopReason reason = new StopReason(
                            StopCategory.GATE_REJECTED,
                            "破坏性工具 " + req.name() + " 缺少必要参数 url",
                            STOP_GRACE_STEPS);
                    return HookResult.stop(reason);
                }
            }
        }
        return HookResult.ok();
    }
}
