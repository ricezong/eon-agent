package cn.kong.eon.agent.capability.gurad;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 门禁校验能力模块（GUARD 层，order=20）。
 * beforeToolExecution 中检查破坏性工具调用的必要参数。
 */
public class GateKeeperCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(GateKeeperCapability.class);

    public static final String CATEGORY_GATE_REJECTED = "GATE_REJECTED";

    private final ToolRegistry toolRegistry;
    private final boolean autoApprove;

    public GateKeeperCapability(ToolRegistry toolRegistry, boolean autoApprove) {
        this.toolRegistry = toolRegistry;
        this.autoApprove = autoApprove;
    }

    @Override public String name() { return "GateKeeper"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public Layer layer() { return Layer.GUARD; }
    @Override public int orderInLayer() { return 20; }

    @Override
    public CapabilityResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return CapabilityResult.ok();

        for (ToolExecutionRequest req : requests) {
            if (toolRegistry.isDestructive(req.name())) {
                log.warn("Destructive tool call: {} | args: {} | turn: {}",
                        req.name(), req.arguments(), state.getTurnCount());

                if (!autoApprove) {
                    // 生产环境可接入审批流
                }

                // 破坏性工具必须有 url 参数
                if (req.arguments() == null || !req.arguments().contains("url")) {
                    return CapabilityResult.abort(CATEGORY_GATE_REJECTED,
                            "破坏性工具 " + req.name() + " 缺少必要参数 url");
                }
            }
        }
        return CapabilityResult.ok();
    }
}
