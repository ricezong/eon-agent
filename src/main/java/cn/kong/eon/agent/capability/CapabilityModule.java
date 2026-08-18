package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * 能力模块接口。
 *
 * <p>能力模块是可插拔的横切关注点，按 {@link Layer} 分层、按 {@link #orderInLayer()} 微调顺序。
 * EonAgent 在 Agent Loop 的 4 个 Hook 点统一调度所有激活的能力模块。</p>
 *
 * <h3>四个 Hook 点</h3>
 * <ol>
 *   <li>{@link #beforeModelCall}：模型调用前。可组装上下文、修改工具列表。
 *       典型用途：预算检查（BudgetGuard）、上下文压缩（ContextCompactor）、
 *       nudge 渲染（NudgeRenderer）、navigator 渲染（TodoNavigator）。</li>
 *   <li>{@link #afterModelCall}：模型调用后。可解析输出、决定是否进入扩展循环。
 *       典型用途：重复调用检测（LoopGuard）。</li>
 *   <li>{@link #beforeToolExecution}：工具执行前。可校验工具调用、拒绝破坏性操作。
 *       典型用途：门禁校验（GateKeeperCapability）。
 *       <p><b>注意</b>：此 Hook 替代了原 GateKeeperCapability 的特殊 check() 调用路径，
 *       统一到标准 Hook 机制，使 EonAgent 真正实现单入口。</p></li>
 *   <li>{@link #afterToolExecution}：工具执行后。可更新失败计数、检查熔断、保存快照。
 *       典型用途：失败熔断（LoopGuard）、Todo 激活标记（TodoNavigator）、
 *       Checkpoint 保存（CheckpointManager）。</li>
 * </ol>
 *
 * <h3>执行顺序</h3>
 * <p>所有 Hook 循环都按 {@link #layer()} 跨层排序 + {@link #orderInLayer()} 同层微调，
 * 由 EonAgent 的 getSortedModules() 统一计算。排序规则：</p>
 * <ul>
 *   <li>跨层：GUARD(1) → CONTEXT(2) → RENDER(3) → OBSERVE(4) → RECORD(5)</li>
 *   <li>同层：orderInLayer 数值小先执行，默认 100。</li>
 * </ul>
 * <p>这样保证 BudgetGuard(GUARD) 必先于 NudgeRenderer(RENDER) 执行，
 * 解决了原 Priority 设计中"同优先级隐含顺序未强制保证"的问题。</p>
 *
 * <h3>中断机制</h3>
 * <p>能力模块通过返回 {@link CapabilityResult#abort(String, String)} 中断循环，
 * 而非抛异常。EonAgent 统一通过 {@link CapabilityResult#isAbort()} 判断并路由处理。
 * 这解耦了能力模块与引擎：新增守卫模块只需约定新的 category 字符串，无需改 EonAgent 的 catch 块。</p>
 *
 * <h3>isActive 策略</h3>
 * <p>各模块自行决定激活条件：</p>
 * <ul>
 *   <li>始终激活：BudgetGuard、ContextCompactor、NudgeRenderer、LoopGuard、GateKeeperCapability。</li>
 *   <li>按状态激活：TodoNavigator（todo_write 调用后激活）。</li>
 *   <li>按配置激活：CheckpointManager（mode.checkpoint_enabled=true 时激活）。</li>
 * </ul>
 */
public interface CapabilityModule {

    /**
     * 模块名称（用于日志和调试）。
     */
    String name();

    /**
     * 是否激活（由当前状态和 Profile 决定）。
     */
    boolean isActive(SessionState state);

    /**
     * 职责层（决定跨层执行顺序）。
     *
     * @see Layer
     */
    Layer layer();

    /**
     * 同层内顺序，数值小先执行。默认 100。
     *
     * <p>典型用法（GUARD 层内）：</p>
     * <ul>
     *   <li>BudgetGuard: 10（最先检查预算）</li>
     *   <li>GateKeeperCapability: 20（门禁校验）</li>
     *   <li>LoopGuard: 30（循环检测）</li>
     * </ul>
     */
    default int orderInLayer() { return 100; }

    /**
     * Hook 1：模型调用前。可组装上下文、修改工具列表。
     *
     * @return {@link CapabilityResult#ok()} 继续；{@link CapabilityResult#abort} 中断循环。
     */
    default CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        return CapabilityResult.ok();
    }

    /**
     * Hook 2：模型调用后。可解析输出、决定是否进入扩展循环。
     *
     * @return {@link CapabilityResult#ok()} 继续；{@link CapabilityResult#abort} 中断循环。
     */
    default CapabilityResult afterModelCall(SessionState state, LlmResponse response) {
        return CapabilityResult.ok();
    }

    /**
     * Hook 3：工具执行前。可校验工具调用、拒绝破坏性操作。
     *
     * <p>此 Hook 替代原 GateKeeperCapability 的特殊 check() 调用路径，
     * 统一到标准 Hook 机制。</p>
     *
     * @param requests 模型本次请求的工具调用列表（非 null，可能为空）。
     * @return {@link CapabilityResult#ok()} 放行；{@link CapabilityResult#abort} 拒绝执行。
     */
    default CapabilityResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        return CapabilityResult.ok();
    }

    /**
     * Hook 4：工具执行后。可更新失败计数、检查熔断、保存快照。
     *
     * @param toolName 工具名。
     * @param success  执行是否成功。
     * @return {@link CapabilityResult#ok()} 继续；{@link CapabilityResult#abort} 中断循环。
     */
    default CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        return CapabilityResult.ok();
    }
}
