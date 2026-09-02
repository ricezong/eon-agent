package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 工具参数可恢复性标记（入站规则）。判定该参数块的内容在磁盘上是否另有完整副本。
 * <p>
 * 判定条件为两个的同时成立：工具会把调用参数完整持久化到磁盘，且该调用确实执行成功。
 * 失败的调用没有真正落盘，此时清空参数块会永久丢失内容。
 */
public class ToolArgsRecoverRule implements IngestRule {

    @Override
    public String name() {
        return "ToolArgsRecover";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_ARGS;
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        // 当前具是否会把它的调用参数完整持久化到磁盘。落盘的话 TOOL_ARGS 可以替换为占位符
        boolean persisted = block.toolName() != null && ctx.toolSupport().persistsArgs(block.toolName());
        block.setRecoverable(persisted && ctx.succeeded(block.toolCallId()));
    }
}
