package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;

/**
 * 在站上下文规则。取代原先硬编码的 Snip / Prune / Summarize 三段 if-else。
 * <p>
 * 规则自带触发条件，策略机只负责"谁该跑"，不再负责"怎么压"。
 * <p>
 * 新增一种处置方式 = 加一个实现类，无需改动策略机、Hook 或配置读取逻辑。
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
