package cn.kong.eon.agent.capability;

/**
 * 能力模块 Hook 返回值。
 * ok() 继续；abort(category, reason) 中断循环。
 *
 * category 约定：BUDGET_EXCEEDED / LOOP_DETECTED / GATE_REJECTED。
 * 新增守卫模块只需约定新 category，在 EonAgent.formatAbort 中添加分支即可。
 */
public final class CapabilityResult {

    private final boolean abort;
    private final String category;
    private final String reason;

    private CapabilityResult(boolean abort, String category, String reason) {
        this.abort = abort;
        this.category = category;
        this.reason = reason;
    }

    public static CapabilityResult ok() {
        return new CapabilityResult(false, null, null);
    }

    /** 中断循环，附带类别和原因。 */
    public static CapabilityResult abort(String category, String reason) {
        return new CapabilityResult(true, category, reason);
    }

    public boolean isAbort() { return abort; }
    public String getCategory() { return category; }
    public String getReason() { return reason; }
}
