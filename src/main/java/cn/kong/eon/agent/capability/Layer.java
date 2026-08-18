package cn.kong.eon.agent.capability;

/**
 * 能力模块职责层。
 *
 * <p>决定能力模块在 Agent Loop 中的执行顺序（跨层排序）。
 * 同层内的执行顺序由 {@link CapabilityModule#orderInLayer()} 决定。</p>
 *
 * <h3>层定义</h3>
 * <ul>
 *   <li>{@link #GUARD}：守卫层。预算/门禁/循环检测，可中断循环。最先执行。</li>
 *   <li>{@link #CONTEXT}：上下文层。压缩、摘要等对 transcript 的处理。</li>
 *   <li>{@link #RENDER}：渲染层。将运行时状态（nudge/navigator）渲染到 ContextBuilder。</li>
 *   <li>{@link #OBSERVE}：观察层。预留，用于未来指标采集（当前无模块使用）。</li>
 *   <li>{@link #RECORD}：记录层。Checkpoint、审计等持久化操作。最后执行。</li>
 * </ul>
 *
 * <h3>设计动机</h3>
 * <p>替代原 {@code Priority(HIGH/NORMAL/LOW)} 三档设计。原设计存在两个问题：</p>
 * <ol>
 *   <li>语义模糊：priority 只表达"先后"，不表达"职责层"，导致 BudgetGuard(HIGH) 和
 *       NudgeRenderer(HIGH) 同优先级，但 BudgetGuard 必须先执行（它产生 nudge，
 *       NudgeRenderer 渲染 nudge）—— 这个隐含顺序没有强制保证。</li>
 *   <li>扩展性差：新增模块时难以判断应该分配 HIGH 还是 NORMAL。</li>
 * </ol>
 * <p>Layer 通过"职责层"语义，使排序意图显式化：</p>
 * <ul>
 *   <li>BudgetGuard 属于 GUARD 层（order=1），NudgeRenderer 属于 RENDER 层（order=3），
 *       跨层排序保证 BudgetGuard 必先于 NudgeRenderer 执行。</li>
 *   <li>同层内用 orderInLayer 微调，如 GUARD 层内 BudgetGuard(10) → GateKeeper(20) → LoopGuard(30)。</li>
 * </ul>
 */
public enum Layer {
    /** 守卫层：预算/门禁/循环检测，可中断循环。最先执行。 */
    GUARD(1),

    /** 上下文层：压缩、摘要等对 transcript 的处理。 */
    CONTEXT(2),

    /** 渲染层：将运行时状态（nudge/navigator）渲染到 ContextBuilder。 */
    RENDER(3),

    /** 观察层：预留，用于未来指标采集（当前无模块使用）。 */
    OBSERVE(4),

    /** 记录层：Checkpoint、审计等持久化操作。最后执行。 */
    RECORD(5);

    /** 层级顺序，数值小先执行。 */
    public final int order;

    Layer(int order) {
        this.order = order;
    }
}
