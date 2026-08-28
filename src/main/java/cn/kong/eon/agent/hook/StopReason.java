package cn.kong.eon.agent.hook;

/**
 * 优雅停止原因。携带终止类别、可读消息和 grace steps。
 */
public final class StopReason {

    private final StopCategory category;
    private final String message;
    private final int graceSteps;

    /**
     * @param category   终止类别
     * @param message    可读原因（展示给用户）
     * @param graceSteps 给 LLM 整理输出的额外轮次；0 表示立即硬终止
     */
    public StopReason(StopCategory category, String message, int graceSteps) {
        this.category = category;
        this.message = message;
        this.graceSteps = graceSteps;
    }

    public StopCategory getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public int getGraceSteps() {
        return graceSteps;
    }

    /**
     * 构建收尾 nudge 文本，引导 LLM 直接输出总结回复。
     */
    public String toNudgeText() {
        return String.format(
                "⚠️ 任务因以下原因即将终止，请在剩余 %d 轮内整理已有信息，直接输出最终总结回复。\n" +
                        "终止原因: %s\n" +
                        "要求: 在回复中说明已完成的工作、未完成的工作、以及后续建议。\n" +
                        "不要再发起新的工具调用，用已有信息整理总结即可。",
                graceSteps, message);
    }
}