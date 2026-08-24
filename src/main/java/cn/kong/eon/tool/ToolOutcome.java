package cn.kong.eon.tool;

/**
 * 工具执行结果。封装成功/失败状态与内容。
 * 消费方通过 {@link #success()} 判定状态。
 */
public record ToolOutcome(boolean success, String content) {

    public static ToolOutcome success(String content) {
        return new ToolOutcome(true, content != null ? content : "");
    }

    public static ToolOutcome failure(String content) {
        return new ToolOutcome(false, content != null ? content : "");
    }
}
