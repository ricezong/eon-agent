package cn.kong.eon.agent.turn;

/**
 * 循环控制返回值。Continue 继续，Skip 跳过后续阶段直接进入下一轮，Exit 携带输出文本退出。
 */
public sealed interface TurnOutcome permits TurnOutcome.Continue, TurnOutcome.Skip, TurnOutcome.Exit {

    /** 继续循环。 */
    record Continue() implements TurnOutcome {
    }

    /** 跳过当前 Turn 后续阶段，直接进入下一轮（由 Hook skip 触发）。 */
    record Skip() implements TurnOutcome {
    }

    /** 退出循环，携带最终输出文本。 */
    record Exit(String output) implements TurnOutcome {
    }

    /** 是否为 Exit。 */
    default boolean isExit() {
        return this instanceof Exit;
    }
}
