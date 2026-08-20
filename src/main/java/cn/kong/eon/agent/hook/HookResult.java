package cn.kong.eon.agent.hook;

/**
 * Hook 执行返回值。
 * <ul>
 *   <li>{@link #ok()} — 继续，一切正常</li>
 *   <li>{@link #stop(StopReason)} — 请求优雅停止：注入收尾指令让 LLM 调用 finish 总结，
 *       超过 graceSteps 仍不调用则硬终止</li>
 * </ul>
 *
 * <p>所有"需要终止"的场景统一走 stop，
 * 由 EonAgent 控制流决定是给 LLM 最后一次总结机会还是直接硬终止。</p>
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

    /**
     * 请求优雅停止。EonAgent 会注入收尾 nudge，给 LLM graceSteps 轮调用 finish 的机会。
     *
     * @param stopReason 停止原因（含类别、消息、graceSteps）
     */
    public static HookResult stop(StopReason stopReason) {
        return new HookResult(Action.STOP, stopReason);
    }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isStop() { return action == Action.STOP; }

    public StopReason getStopReason() { return stopReason; }

    /** Hook 动作枚举。 */
    public enum Action {
        CONTINUE,
        STOP
    }
}
