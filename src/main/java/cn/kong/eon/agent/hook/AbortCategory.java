package cn.kong.eon.agent.hook;

/**
 * Hook 中断类别枚举。
 */
public enum AbortCategory {
    BUDGET_EXCEEDED("预算超限（任务终止）"),
    LOOP_DETECTED("检测到死循环"),
    GATE_REJECTED("门禁拒绝");

    private final String displayName;

    AbortCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
