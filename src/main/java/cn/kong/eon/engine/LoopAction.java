package cn.kong.eon.engine;

/**
 * 循环控制返回值。CONTINUE 继续，SKIP 跳过后续阶段直接进入下一轮，EXIT 携带输出文本退出。
 * <p>
 * CONTINUE 和 SKIP 为无状态单例常量，EXIT 携带输出文本。
 */
public final class LoopAction {

    public enum Kind { CONTINUE, SKIP, EXIT }

    /** 继续循环（无状态常量）。 */
    public static final LoopAction CONTINUE = new LoopAction(Kind.CONTINUE, null);
    /** 跳过当前 Turn 后续阶段，直接进入下一轮（无状态常量）。 */
    public static final LoopAction SKIP = new LoopAction(Kind.SKIP, null);

    private final Kind kind;
    private final String output;

    private LoopAction(Kind kind, String output) {
        this.kind = kind;
        this.output = output;
    }

    /** 退出循环，携带最终输出文本。 */
    public static LoopAction exit(String output) {
        return new LoopAction(Kind.EXIT, output);
    }

    public boolean isExit() {
        return kind == Kind.EXIT;
    }

    public boolean isSkip() {
        return kind == Kind.SKIP;
    }

    /** 仅 EXIT 有值，其他类型返回 null。 */
    public String output() {
        return output;
    }
}
