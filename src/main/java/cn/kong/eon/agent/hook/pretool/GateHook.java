package cn.kong.eon.agent.hook.pretool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.stop.StopCategory;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.session.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 门禁校验（PreTool, order=20）。
 * 对破坏性工具执行审批策略：auto_approve_destructive=true 时仅记日志，
 * false 时触发 GATE_REJECTED 终止，等待用户介入。
 */
public class GateHook implements Hook.PreToolHook {
    private static final Logger log = LoggerFactory.getLogger(GateHook.class);

    private final ToolRegistry toolRegistry;
    private final boolean autoApproveDestructive;

    public GateHook(ToolRegistry toolRegistry, AgentConfig config) {
        this.toolRegistry = toolRegistry;
        this.autoApproveDestructive = config.getTools().isAutoApproveDestructive();
    }

    @Override
    public String name() {
        return "Gate";
    }

    @Override
    public boolean active(SessionState state) {
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

            if (autoApproveDestructive) {
                log.warn("[Gate] 破坏性工具 '{}' 已自动批准 | 参数: {} | turn: {}", req.name(), req.arguments(), state.getTurnCount());
            } else {
                log.warn("[Gate] 破坏性工具 '{}' 需要审批，已拒绝 | 参数: {} | turn: {}", req.name(), req.arguments(), state.getTurnCount());
                return HookResult.stop(StopCategory.GATE_REJECTED, StopCategory.GATE_REJECTED.format(req.name()));
            }
        }
        return HookResult.ok();
    }
}
