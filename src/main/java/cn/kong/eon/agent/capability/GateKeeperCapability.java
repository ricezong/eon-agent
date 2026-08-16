package cn.kong.eon.agent.capability;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 门禁校验能力模块。
 *
 * 始终激活。在工具执行前检查破坏性操作。
 * 非破坏性工具直接放行，破坏性工具执行前置断言。
 */
public class GateKeeperCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(GateKeeperCapability.class);

    private final ToolRegistry toolRegistry;
    private final boolean autoApprove;

    public GateKeeperCapability(ToolRegistry toolRegistry, boolean autoApprove) {
        this.toolRegistry = toolRegistry;
        this.autoApprove = autoApprove;
    }

    @Override
    public String name() { return "GateKeeper"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    /**
     * 检查工具调用列表是否有破坏性操作。
     * 返回 null 表示通过，返回字符串表示拒绝原因。
     */
    public String check(List<ToolExecutionRequest> requests, SessionState state) {
        if (requests == null || requests.isEmpty()) return null;

        for (ToolExecutionRequest req : requests) {
            if (toolRegistry.isDestructive(req.name())) {
                log.warn("Destructive tool call: {} | args: {} | turn: {}",
                        req.name(), req.arguments(), state.getTurnCount());

                if (!autoApprove) {
                    // 生产环境可接入审批流，MVP 阶段直接放行
                }

                // 前置断言：破坏性工具的必要参数不能为空
                if (req.arguments() == null || !req.arguments().contains("url")) {
                    return "破坏性工具 " + req.name() + " 缺少必要参数 url";
                }
            }
        }
        return null;
    }
}
