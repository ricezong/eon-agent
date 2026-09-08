package cn.kong.eon.agent.hook;

import cn.kong.eon.agent.stop.StopCategory;

/**
 * Hook 执行返回值。ok() 继续，skip() 跳过当前 Turn 后续阶段直接进入下一轮，stop() 请求终止。
 */
public final class HookResult {

    private final Action action;
    private final StopCategory category;
    private final String message;

    private HookResult(Action action, StopCategory category, String message) {
        this.action = action;
        this.category = category;
        this.message = message;
    }

    /** 正常继续，走后续阶段。 */
    public static HookResult ok() {
        return new HookResult(Action.CONTINUE, null, null);
    }

    /** 跳过当前 Turn 的后续阶段，直接进入下一轮循环（不退出）。 */
    public static HookResult skip() {
        return new HookResult(Action.SKIP, null, null);
    }

    /** 请求停止。message 由调用方通过 {@link StopCategory#format} 生成。 */
    public static HookResult stop(StopCategory category, String message) {
        return new HookResult(Action.STOP, category, message);
    }

    public boolean isStop() {
        return action == Action.STOP;
    }

    public boolean isSkip() {
        return action == Action.SKIP;
    }

    public StopCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public enum Action {
        CONTINUE,
        SKIP,
        STOP
    }
}
