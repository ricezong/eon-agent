package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 上下文里所有 XML 标签的<b>唯一定义处与唯一渲染出口</b>。
 * <p>
 * 标签分两层：
 * <ul>
 *   <li><b>段级</b>：摘要、记忆、任务、提醒。这四段不是对话内容而是注入信息，
 *       由 {@link ContextBuilder} 在组装时包标签，写入方只交纯内容。</li>
 *   <li><b>块级</b>：对话块的可视化边界。块的 {@code text} 只存内容本身，
 *       边界与元数据在渲染时由 {@link #render(ContextBlock)} 补上，
 *       所以入站规则、压缩策略都不需要往文本里拼任何前缀或外壳。</li>
 * </ul>
 * 集中带来的直接好处：改标签只需改这里；块文本保持"纯内容"，
 * 压缩对文本的截断、清空、骨架化都不会误伤标签；摘要与实际发送给模型的内容形态一致。
 */
public final class ContextTags {

    // ═══════════════ 段级 ═══════════════

    /** 历史摘要段 */
    public static final String SUMMARY = "summary";
    /** 跨会话记忆段 */
    public static final String MEMORIES = "memories";
    /** 任务列表段 */
    public static final String TODO = "todo";
    /** 运行时提醒段 */
    public static final String NUDGES = "nudges";

    // ═══════════════ 块级 ═══════════════

    /** 用户消息块。与系统提示词里"用户指示由 {@code <user_query>} 表示"的约定同名 */
    public static final String USER_QUERY = "user_query";
    /** 助手正文块 */
    public static final String ASSISTANT = "assistant";
    /** 工具调用块。只用于摘要与度量——实际发送的 arguments 必须是裸 JSON，见 BlockProjector */
    public static final String TOOL_CALL = "tool_call";
    /** 工具结果块 */
    public static final String TOOL_RESULT = "tool_result";
    /** 未分类块 */
    public static final String OTHER = "other";

    private ContextTags() {
    }

    /** 包成 {@code <tag>body</tag>}。 */
    public static String wrap(String tag, String body) {
        return "<" + tag + ">\n" + (body == null ? "" : body) + "\n</" + tag + ">";
    }

    /** 包成 {@code <tag attrs>body</tag>}，attrs 自带前导空格。 */
    public static String wrap(String tag, String attrs, String body) {
        String open = (attrs == null || attrs.isEmpty()) ? "<" + tag + ">" : "<" + tag + attrs + ">";
        return open + "\n" + (body == null ? "" : body) + "\n</" + tag + ">";
    }

    /**
     * 块 → 展示形态：按类型包标签，并把块上的元数据落成标签属性。
     *
     * @param block 待渲染的块
     */
    public static String render(ContextBlock block) {
        String text = block.text() == null ? "" : block.text();
        return switch (block.kind()) {
            case USER_INPUT -> wrap(USER_QUERY, text);
            case AI_TEXT -> wrap(ASSISTANT, text);
            case TOOL_ARGS -> wrap(TOOL_CALL, nameAttr(block), text);
            case TOOL_RESULT -> renderToolResult(block, text);
            case OTHER -> wrap(OTHER, text);
        };
    }

    /**
     * 工具结果：工具名、执行状态、截断前的长度、磁盘副本引用都以属性表达，
     * 正文保持内容本身。有副本时补一行取回指引——落盘后的截断与 PRUNE 后的清空
     * 都靠这一行告诉模型去哪里取全文，不需要各处置点各自写一遍。
     */
    private static String renderToolResult(ContextBlock block, String text) {
        StringBuilder attrs = new StringBuilder();
        if (block.toolName() != null) {
            attrs.append(" name=\"").append(block.toolName()).append('"');
        }
        attrs.append(" status=\"").append(Boolean.FALSE.equals(block.success()) ? "failed" : "success").append('"');
        if (block.originalChars() > text.length()) {
            attrs.append(" truncated_from=\"").append(block.originalChars()).append('"');
        }
        if (block.refId() != null) {
            attrs.append(" ref=\"").append(block.refId()).append('"');
        }

        String body = block.refId() == null
                ? text
                : text + "\n[完整内容已落盘，可用 read_file 读取 artifact 引用 " + block.refId() + "]";
        return wrap(TOOL_RESULT, attrs.toString(), body);
    }

    private static String nameAttr(ContextBlock block) {
        return block.toolName() == null ? "" : " name=\"" + block.toolName() + "\"";
    }
}
