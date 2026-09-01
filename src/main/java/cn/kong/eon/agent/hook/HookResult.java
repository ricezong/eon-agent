package cn.kong.eon.agent.hook;

/**
 * Hook 执行返回值。ok() 继续，stop() 请求终止。
 * 所有终止场景统一走 stop，由 EonAgent 执行硬终止。
 */
public final class HookResult {

    private final Action action;
    private final StopReason stopReason;

    private HookResult(Action action, StopReason stopReason) {
        this.action = action;
        this.stopReason = stopReason;
    }

    /** 继续，一切正常。 */
    public static HookResult ok() {
        return new HookResult(Action.CONTINUE, null);
    }

    /** 请求停止。EonAgent 直接硬终止。 */
    public static HookResult stop(StopReason stopReason) {
        return new HookResult(Action.STOP, stopReason);
    }

    public boolean isStop() {
        return action == Action.STOP;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    /** Hook 动作枚举。 */
    public enum Action {
        CONTINUE,
        STOP
    }
}
