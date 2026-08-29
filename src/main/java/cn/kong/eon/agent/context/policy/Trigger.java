package cn.kong.eon.agent.context.policy;

/**
 * 规则触发条件。规则<b>自带</b>触发声明，策略机不再需要知道"有几种压缩、阈值是多少"。
 * <p>
 * 这条抽象直接消掉了一个历史病灶：过去"按轮数压缩"的意图，
 * 只能通过伪造一个水位值来复用现成代码路径
 * （{@code effectiveWaterLevel = waterTriggered ? waterLevel : snipThreshold}），
 * 代价是轮数触发被永久钉死在 Snip 级，结构上跨不到 Prune / Summarize。
 * 现在每种触发都是一等类型，规则自己声明支持哪种。
 */
public sealed interface Trigger {

    /** 入站触发。由 ContextPipeline 执行，策略机不处理 */
    record Ingest() implements Trigger {
    }

    /** 水位触发：上下文水位达到阈值 */
    record WaterLevel(double threshold) implements Trigger {
    }

    /**
     * 轮数触发：距上次压缩的轮数达到 baseTurns × multiplier。
     * <p>
     * multiplier 让三级规则在同一套轮数节奏上形成阶梯：
     * Snip ×1、Prune ×2、Summarize ×3。
     */
    record TurnInterval(int baseTurns, int multiplier) implements Trigger {
    }

    /**
     * 预算投影触发：按当前单轮成本，剩余预算支撑不了 minRemainingTurns 轮。
     * <p>
     * 这是"预算感知"的入口。它与水位触发的区别在于：
     * 水位是<b>瞬时大小</b>，预算是<b>大小对时间的积分</b>。
     * 水位不高但增速快时，预算投影会先告警。
     */
    record BudgetProjection(double minRemainingTurns) implements Trigger {
    }
}
