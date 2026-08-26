package cn.kong.eon.agent.hook;

/**
 * 优雅停止类别枚举，用于分类终止原因并展示给用户。
 */
public enum StopCategory {
    BUDGET_EXCEEDED("预算超限"),
    LOOP_DETECTED("检测到死循环"),
    GATE_REJECTED("门禁拒绝"),
    FAILURE_BREAKER("失败熔断"),
    MAX_STEPS_REACHED("达到最大步数"),
    UNEXPECTED_ERROR("执行异常");

    private final String displayName;

    StopCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
