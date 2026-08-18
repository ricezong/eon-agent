package cn.kong.eon.agent.capability;

/**
 * 能力模块职责层，决定跨层执行顺序。同层内按 orderInLayer 微调。
 *
 * GUARD(1)   — 守卫：预算/门禁/循环检测
 * CONTEXT(2) — 上下文处理：压缩、摘要
 * RENDER(3)  — 渲染：nudge/navigator 注入到 ContextBuilder
 * OBSERVE(4) — 观察：预留，用于指标采集
 * RECORD(5)  — 记录：Checkpoint、审计
 */
public enum Layer {
    GUARD(1),
    CONTEXT(2),
    RENDER(3),
    OBSERVE(4),
    RECORD(5);

    public final int order;

    Layer(int order) {
        this.order = order;
    }
}
