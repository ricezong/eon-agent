package cn.kong.eon.agent.context.policy;

import java.util.List;

/**
 * 在站上下文规则。取代原先硬编码的 Snip / Prune / Summarize 三段 if-else。
 * <p>
 * 规则自带三件东西：处置级别、触发条件、作用对象。
 * 策略机只负责"谁该跑"，不再负责"怎么压"。
 * <p>
 * 新增一种处置方式 = 加一个实现类，无需改动策略机、Hook 或配置读取逻辑。
 */
public interface ContextRule {

    /** Snip 级 */
    int LEVEL_SNIP = 0;
    /** Prune 级 */
    int LEVEL_PRUNE = 1;
    /** Summarize 级 */
    int LEVEL_SUMMARIZE = 2;
    /** 无损级：不属于有损阶梯，任何时候都可安全执行 */
    int LEVEL_LOSSLESS = -1;

    String name();

    /**
     * 处置级别，决定执行顺序与轮数触发的阶梯位置。
     * 有损规则取 LEVEL_SNIP / LEVEL_PRUNE / LEVEL_SUMMARIZE；无损规则取 LEVEL_LOSSLESS。
     */
    int level();

    /**
     * 触发条件。规则自己声明在什么情况下应该跑，任一满足即执行。
     * <p>
     * 返回列表而非单个值，是因为同一处置往往有多个入口：
     * Snip 既可由水位触发，也可由轮数节奏触发。
     */
    List<Trigger> triggers();

    /**
     * 执行处置，就地改写窗口中的块。
     */
    RuleOutcome apply(RuleContext ctx);
}
