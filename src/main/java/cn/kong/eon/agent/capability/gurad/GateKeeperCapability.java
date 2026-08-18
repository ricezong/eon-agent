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
 * 门禁校验能力模块。
 *
 * <p>属于 {@link Layer#GUARD} 守卫层，orderInLayer=20（GUARD 层内第二执行，在 BudgetGuard 之后、LoopGuard 之前）。
 * 始终激活。在 {@link #beforeToolExecution} 中检查破坏性操作。
 * 非破坏性工具直接放行，破坏性工具执行前置断言。</p>
 *
 * <h3>重构说明（关键）</h3>
 * <p>原设计中，GateKeeperCapability 不走标准 Hook 机制，而是通过 EonAgent 的
 * {@code findGateKeeper()} 类型查找 + {@code check()} 直接调用，导致 EonAgent 出现特殊路径，
 * 违背"单入口"设计。</p>
 *
 * <p>重构后，删除 {@code check()} 方法，改为实现标准 {@link #beforeToolExecution} Hook。
 * EonAgent 在 afterModelCall 之后、executeTools 之前统一调用所有能力模块的 beforeToolExecution，
 * GateKeeper 通过返回 {@link CapabilityResult#abort} 拒绝破坏性操作。</p>
 *
 * <ul>
 *   <li>priority()=NORMAL → layer()=GUARD + orderInLayer()=20。</li>
 *   <li>删除 check() 方法，新增 beforeToolExecution Hook。</li>
 *   <li>返回值：null(通过)/String(拒绝原因) → CapabilityResult.ok()/abort("GATE_REJECTED", reason)。</li>
 * </ul>
 */
public class GateKeeperCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(GateKeeperCapability.class);

    /** 中断类别：门禁拒绝。 */
    public static final String CATEGORY_GATE_REJECTED = "GATE_REJECTED";

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

    @Override
    public Layer layer() { return Layer.GUARD; }

    @Override
    public int orderInLayer() { return 20; }

    /**
     * Hook 3：工具执行前。检查工具调用列表是否有破坏性操作。
     *
     * <p>非破坏性工具直接放行，破坏性工具执行前置断言：
     * 必要参数（url）不能为空。</p>
     *
     * @return {@link CapabilityResult#ok()} 放行；{@link CapabilityResult#abort} 拒绝执行。
     */
    @Override
    public CapabilityResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return CapabilityResult.ok();

        for (ToolExecutionRequest req : requests) {
            if (toolRegistry.isDestructive(req.name())) {
                log.warn("Destructive tool call: {} | args: {} | turn: {}",
                        req.name(), req.arguments(), state.getTurnCount());

                if (!autoApprove) {
                    // 生产环境可接入审批流，MVP 阶段直接放行
                }

                // 前置断言：破坏性工具的必要参数不能为空
                if (req.arguments() == null || !req.arguments().contains("url")) {
                    return CapabilityResult.abort(CATEGORY_GATE_REJECTED,
                            "破坏性工具 " + req.name() + " 缺少必要参数 url");
                }
            }
        }
        return CapabilityResult.ok();
    }
}
