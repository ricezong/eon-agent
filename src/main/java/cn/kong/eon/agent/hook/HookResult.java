package cn.kong.eon.agent.hook;

import cn.kong.eon.agent.support.StopCategory;

/**
 * Hook 执行返回值。ok() 继续，stop() 请求终止。
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

    public static HookResult ok() {
        return new HookResult(Action.CONTINUE, null, null);
    }

    /** 请求停止。message 由调用方通过 {@link StopCategory#format} 生成。 */
    public static HookResult stop(StopCategory category, String message) {
        return new HookResult(Action.STOP, category, message);
    }

    public boolean isStop() {
        return action == Action.STOP;
    }

    public StopCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public enum Action {
        CONTINUE,
        STOP
    }
}
