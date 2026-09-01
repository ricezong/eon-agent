package cn.kong.eon.tool;

/**
 * 工具执行结果。封装成功/失败状态与内容。
 */
public record ToolOutcome(boolean success, String content) {

    /** 构建成功结果。 */
    public static ToolOutcome success(String content) {
        return new ToolOutcome(true, content != null ? content : "");
    }

    /** 构建失败结果。 */
    public static ToolOutcome failure(String content) {
        return new ToolOutcome(false, content != null ? content : "");
    }
}
