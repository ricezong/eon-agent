package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 工具结果状态标记（入站规则）。把本轮的执行结果记到块上。
 */
public class ToolResultStatusRule implements IngestRule {

    @Override
    public String name() {
        return "ToolResultStatus";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT;
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        block.setSuccess(ctx.succeeded(block.toolCallId()));
    }
}
