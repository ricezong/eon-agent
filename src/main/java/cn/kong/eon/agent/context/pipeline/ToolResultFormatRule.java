package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 工具结果格式化（order=20）。
 * <p>
 * 给结果套上统一外壳：工具名、状态、内容、截断提示与 artifact 引用。
 * 只做格式化，不做任何大小决策——决策属于 {@link ArtifactSpillRule}。
 * <p>
 * 原先这段逻辑和落盘决策混在同一个 {@code render()} 方法里，
 * 导致"格式化"和"上下文大小控制"两件事无法单独演进。
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

        boolean success = ctx.succeeded(block.toolCallId());
        // 把执行结果记到块上：无损卸载规则据此判断"参数是否真的落盘"
        block.setSuccess(success);

        StringBuilder sb = new StringBuilder(display.length() + 160);
        sb.append("[Tool result] ").append(toolName).append('\n');
        sb.append("├─ 状态: ").append(success ? "成功" : "失败").append('\n');
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
