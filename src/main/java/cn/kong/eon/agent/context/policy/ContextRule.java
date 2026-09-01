package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;

/**
 * 运行时压缩规则接口。在 PreModel 阶段对窗口做就地改写。
 * 规则按 {@link ContextPolicy} 中的声明顺序执行。
 */
public interface ContextRule {

    String name();

    /**
     * 触发条件：规则自己声明在什么情况下应该跑。
     * <p>
     * 策略机传入当前度量和距上次压缩的轮数，规则自己判断。
     */
    boolean shouldFire(ContextMetrics metrics, int turnsSinceLastCompress);

    /**
     * 执行处置，就地改写窗口中的块。
     */
    PolicyResult apply(RuleContext ctx);
}
