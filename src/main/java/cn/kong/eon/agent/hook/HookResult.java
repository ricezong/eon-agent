package cn.kong.eon.agent.hook;

/**
 * Hook 执行返回值。ok() 继续；abort(category, reason) 中断循环。
 */
public final class HookResult {

    private final boolean abort;
    private final AbortCategory category;
    private final String reason;

    private HookResult(boolean abort, AbortCategory category, String reason) {
        this.abort = abort;
        this.category = category;
        this.reason = reason;
    }

    public static HookResult ok() {
        return new HookResult(false, null, null);
    }

    /** 中断循环，附带类别和原因。 */
    public static HookResult abort(AbortCategory category, String reason) {
        return new HookResult(true, category, reason);
    }

    public boolean isAbort() { return abort; }
    public AbortCategory getCategory() { return category; }
    public String getReason() { return reason; }
}
