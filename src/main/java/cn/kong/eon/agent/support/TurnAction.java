package cn.kong.eon.agent.support;

/**
 * executeTurn 的返回值，表达循环控制语义。Continue 继续，Exit 退出并携带输出。
 */
public sealed interface TurnAction permits TurnAction.Continue, TurnAction.Exit {

    /**
     * 继续下一轮循环。
     */
    record Continue() implements TurnAction {
    }

    /**
     * 退出循环，携带最终输出文本。
     */
    record Exit(String output) implements TurnAction {
    }
}
