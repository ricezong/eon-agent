package cn.kong.eon.agent.context.block;

/**
 * 内容块类型。一条 AiMessage 拆成 AI_TEXT + TOOL_ARGS，ToolResult 为 TOOL_RESULT。
 */
public enum BlockKind {

    /** 用户输入 */
    USER_INPUT,

    /** 模型输出的正文 */
    AI_TEXT,

    /** 模型输出的工具调用参数块 */
    TOOL_ARGS,

    /** 工具执行结果 */
    TOOL_RESULT,

    /** 兜底类型 */
    OTHER
}
