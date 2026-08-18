package cn.kong.eon.agent.capability;

/**
 * 能力模块 Hook 的返回值。
 *
 * <p>替代原"Hook 中抛异常 + EonAgent 按异常类型 catch"的强耦合设计。
 * 能力模块通过返回 {@link #abort(String, String)} 中断 Agent Loop，
 * EonAgent 统一通过 {@link #isAbort()} 判断并调用 {@link #getCategory()} 路由处理逻辑。</p>
 *
 * <h3>设计动机</h3>
 * <p>原设计中，BudgetGuard 抛 {@code HardBudgetExceededException}、LoopGuard 抛
 * {@code LoopDetectedException}，EonAgent 在 run() 中用两个 catch 块按类型捕获。
 * 这导致：</p>
 * <ol>
 *   <li>新增守卫模块需改 EonAgent 的 catch 块，违背开闭原则。</li>
 *   <li>能力模块与引擎强耦合（异常类定义在能力模块内部，EonAgent 需 import）。</li>
 * </ol>
 * <p>改用 CapabilityResult 后，能力模块只依赖通用返回值，EonAgent 只依赖通用判断逻辑，
 * 双方解耦。新增守卫模块只需约定新的 category 字符串，无需改 EonAgent。</p>
 *
 * <h3>不可变性</h3>
 * <p>CapabilityResult 是不可变对象，通过静态工厂方法创建：</p>
 * <ul>
 *   <li>{@link #ok()}：正常继续，不中断。</li>
 *   <li>{@link #abort(String, String)}：中断循环，附带 category 和 reason。</li>
 * </ul>
 *
 * <h3>category 约定</h3>
 * <p>当前已定义的 category（由 EonAgent 的 formatAbort 路由）：</p>
 * <ul>
 *   <li>{@code BUDGET_EXCEEDED}：预算硬超限（BudgetGuard）。</li>
 *   <li>{@code LOOP_DETECTED}：检测到死循环（LoopGuard）。</li>
 *   <li>{@code GATE_REJECTED}：门禁拒绝（GateKeeperCapability）。</li>
 *   <li>其他：通用"能力中断"提示。</li>
 * </ul>
 */
public final class CapabilityResult {

    private final boolean abort;
    private final String category;
    private final String reason;

    private CapabilityResult(boolean abort, String category, String reason) {
        this.abort = abort;
        this.category = category;
        this.reason = reason;
    }

    /**
     * 正常继续，不中断循环。
     */
    public static CapabilityResult ok() {
        return new CapabilityResult(false, null, null);
    }

    /**
     * 中断循环。
     *
     * @param category 中断类别（如 BUDGET_EXCEEDED / LOOP_DETECTED / GATE_REJECTED），
     *                 用于 EonAgent 路由处理逻辑。
     * @param reason   中断原因（人类可读，会展示给用户）。
     */
    public static CapabilityResult abort(String category, String reason) {
        return new CapabilityResult(true, category, reason);
    }

    /**
     * 是否中断循环。
     */
    public boolean isAbort() {
        return abort;
    }

    /**
     * 获取中断类别（仅 isAbort()=true 时有意义）。
     */
    public String getCategory() {
        return category;
    }

    /**
     * 获取中断原因（仅 isAbort()=true 时有意义）。
     */
    public String getReason() {
        return reason;
    }
}
