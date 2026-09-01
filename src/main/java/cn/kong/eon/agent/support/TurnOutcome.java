package cn.kong.eon.agent.support;

/**
 * 循环控制返回值。Continue 继续，Exit 携带输出文本退出。
 */
public sealed interface TurnOutcome permits TurnOutcome.Continue, TurnOutcome.Exit {

    /** 继续循环。 */
    record Continue() implements TurnOutcome {
    }

    /** 退出循环，携带最终输出文本。 */
    record Exit(String output) implements TurnOutcome {
    }

    /** 是否为 Exit。 */
    default boolean isExit() {
        return this instanceof Exit;
    }
}
