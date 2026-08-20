package cn.kong.eon.agent.hook;

/**
 * 优雅停止原因。携带终止类别、可读消息和 grace steps。
 *
 * <p>EonAgent 收到 stop 后：
 * <ol>
 *   <li>注入收尾 nudge（包含此 reason）到 pendingNudges</li>
 *   <li>设置 state.stopState = REQUESTED，记录 graceSteps</li>
 *   <li>继续循环，给 LLM 调用 finish 的机会</li>
 *   <li>超过 graceSteps 仍未 finish，则用此 reason 做硬终止</li>
 * </ol>
 */
public final class StopReason {

    private final StopCategory category;
    private final String message;
    private final int graceSteps;

    /**
     * @param category   终止类别
     * @param message     可读原因（会展示给用户）
     * @param graceSteps 给 LLM 调用 finish 的额外轮次；0 表示立即硬终止
     */
    public StopReason(StopCategory category, String message, int graceSteps) {
        this.category = category;
        this.message = message;
        this.graceSteps = graceSteps;
    }

    public StopCategory getCategory() { return category; }
    public String getMessage() { return message; }
    public int getGraceSteps() { return graceSteps; }

    /** 构建收尾 nudge 文本，引导 LLM 调用 finish。 */
    public String toNudgeText() {
        return String.format(
                "⚠️ 任务因以下原因即将终止，请立即调用 finish 工具进行总结。\n" +
                "终止原因: %s\n" +
                "要求: 在 summary 中说明已完成的工作、未完成的工作、以及后续建议。" +
                "如果存在 Todo 列表，请在 summary 中包含当前进度。" +
                "将 goal_achieved 设为 false（因为任务被中断）。剩余 %d 轮，请务必在本轮调用 finish。",
                message, graceSteps);
    }

    /** 构建硬终止时的最终输出文本。 */
    public String toFinalText() {
        String displayName = category != null ? category.getDisplayName() : "执行中断";
        return displayName + ": " + message;
    }
}
