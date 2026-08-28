package cn.kong.eon.agent.hook.pretool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 门禁校验（PreTool, order=20）。
 * 对破坏性工具记录审批日志。
 */
public class GateHook implements Hook.PreToolHook {
    private static final Logger log = LoggerFactory.getLogger(GateHook.class);

    private final ToolRegistry toolRegistry;

    public GateHook(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "Gate";
    }

    @Override
    public boolean isActive(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public HookResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return HookResult.ok();

        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.isDestructive(req.name())) continue;

            log.warn("[工具门禁] 破坏性工具 '{}' 已批准 | 参数: {} | turn: {}",
                    req.name(), req.arguments(), state.getTurnCount());
        }
        return HookResult.ok();
    }
}
