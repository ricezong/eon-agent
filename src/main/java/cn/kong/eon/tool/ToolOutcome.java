package cn.kong.eon.tool;

/**
 * 工具执行结果。封装成功/失败状态与内容，替代此前以 [ERROR] 前缀判定成功的隐式约定。
 *
 * <ul>
 *   <li>{@link #success(String)} — 执行成功，携带结果文本</li>
 *   <li>{@link #failure(String)} — 执行失败，携带错误描述</li>
 * </ul>
 *
 * <p>所有工具（本地 + MCP）统一返回此类型，消费方通过 {@link #success()} 判定状态，
 * 不再依赖字符串前缀解析。</p>
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
