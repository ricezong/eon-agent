package cn.kong.eon.tool;

/**
 * 工具执行结果。封装成功/失败状态与内容。
 * 消费方通过 {@link #success()} 判定状态。
 */
public record ToolOutcome(boolean success, String content) {

    /** 工具执行成功。 */
    public static ToolOutcome success(String content) {
        return new ToolOutcome(true, content != null ? content : "");
    }

    /** 工具执行失败。 */
    public static ToolOutcome failure(String content) {
        return new ToolOutcome(false, content != null ? content : "");
    }
}
