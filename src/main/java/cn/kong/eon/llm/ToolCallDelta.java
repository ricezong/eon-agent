package cn.kong.eon.llm;

/**
 * 工具调用的增量片段。模型生成工具参数时逐段回调，
 * 用于在工具真正执行前就向前端反馈「正在生成 xxx 的调用参数」。
 *
 * @param index       同一次响应中多个工具调用的序号
 * @param id          工具调用 ID，与最终 {@code engine.tool_use} 的 tool_use_id 一致
 * @param name        工具名
 * @param partialArgs 参数片段（JSON 的一小段，可能是半个 token）
 */
public record ToolCallDelta(int index, String id, String name, String partialArgs) {
}
