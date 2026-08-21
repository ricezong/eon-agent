package cn.kong.eon.agent;

/**
 * executeTurn 的返回值，表达循环控制语义。
 *
 * <ul>
 *   <li>{@link Continue} — 当前轮次结束，继续下一轮循环</li>
 *   <li>{@link Exit} — 退出循环，携带最终输出文本</li>
 * </ul>
 *
 * <p>替代此前以 {@code null} 表示"继续"、非 null 表示"退出"的隐式约定，
 * 消除空字符串与 {@code null} 的歧义。</p>
 */
public sealed interface TurnAction permits TurnAction.Continue, TurnAction.Exit {

    /** 继续下一轮循环。 */
    record Continue() implements TurnAction {}

    /** 退出循环，携带最终输出文本。 */
    record Exit(String output) implements TurnAction {}
}
