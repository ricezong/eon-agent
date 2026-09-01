package cn.kong.eon.agent.context.block;

/**
 * 内容块类型。上下文的最小语义单位不是"消息"，而是"消息里的内容块"。
 * 一条 AiMessage 会被拆成 1 个 AI_TEXT 块 + N 个 TOOL_ARGS 块；
 * 一条 ToolExecutionResultMessage 是 1 个 TOOL_RESULT 块。
 */
public enum BlockKind {

    /** 系统提示词 / 摘要锚点层 */
    SYSTEM,

    /** 用户输入。逐字保留，摘要会丢失措辞与隐含意图 */
    USER_INPUT,

    /** 模型输出的正文（推理链、结论） */
    AI_TEXT,

    /** 模型输出的工具调用参数块。write 类工具会把整份文件内容塞在这里 */
    TOOL_ARGS,

    /** 工具执行结果 */
    TOOL_RESULT,

    /** 兜底类型 */
    OTHER;

    /** 是否属于工具配对结构（tool_use / tool_result）。 */
    public boolean isToolPaired() {
        return this == TOOL_ARGS || this == TOOL_RESULT;
    }
}
