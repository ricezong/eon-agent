package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 工具结果格式化（入站规则）。为工具结果套上统一的展示外壳，附带执行状态与落盘引用。
 */
public class ToolResultFormatRule implements IngestRule {

    @Override
    public String name() {
        return "ToolResultFormat";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT;
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        String display = block.text();
        String toolName = block.toolName() != null ? block.toolName() : "unknown";
        String refId = block.refId();
        int originalChars = block.originalChars();
        int keepChars = ctx.snipKeepChars() * 2;
        int headChars = keepChars / 2;
        int tailChars = keepChars - headChars;

        boolean succeeded = ctx.succeeded(block.toolCallId());

        StringBuilder sb = new StringBuilder(display.length() + 160);
        sb.append("[Tool result] ").append(toolName).append('\n');
        sb.append("├─ 状态: ").append(succeeded ? "成功" : "失败").append('\n');
        sb.append("├─ 内容:\n").append(display).append('\n');

        if (refId != null) {
            sb.append("├─ 截断提示: 内容过大（").append(originalChars)
                    .append(" 字符），已截断为头部 ").append(headChars)
                    .append(" + 尾部 ").append(tailChars).append(" 字符摘要\n");
            sb.append("└─ 元数据: 完整内容已保存至 artifact://").append(refId)
                    .append("，可用 read_file 工具读取该引用获取完整内容");
        } else {
            sb.append("└─ 元数据: ").append(originalChars).append(" 字符");
        }

        block.setText(sb.toString());
    }
}
