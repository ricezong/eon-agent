package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 工具结果格式化（入站规则）。为工具结果套上统一的展示外壳，附带执行状态与落盘引用。
 */
public class ToolResultFormatRule implements IngestRule {

    private static final String SHELL_PREFIX = "[Tool result] ";

    @Override
    public String name() {
        return "ToolResultFormat";
    }

    /**
     * 只作用于工具结果块。
     * 幂等由结构保证，不靠内容判据：管线是单向闸门，块进窗口后不再回到管线
     * （历史恢复由 explode 直入窗口，压缩由 BlockDisposer 直接改写），
     * 而每次入站的块都由不可变的 ChatMessage 重新爆炸得到，文本不带上一轮处置的痕迹。
     */
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

        StringBuilder sb = new StringBuilder(display.length() + 160);
        sb.append(SHELL_PREFIX).append(toolName).append('\n');
        sb.append("├─ 状态: ").append(ctx.succeeded(block.toolCallId()) ? "成功" : "失败").append('\n');
        sb.append("├─ 内容:\n").append(display).append('\n');

        if (refId != null) {
            // 两个数值都是实测：originalChars 为入站长度，display.length() 为落盘后块里的实际长度
            sb.append("├─ 截断提示: 内容过大（").append(originalChars)
                    .append(" 字符），已截断为 ").append(display.length()).append(" 字符摘要\n");
            sb.append("└─ 元数据: 完整内容已保存至 artifact://").append(refId)
                    .append("，可用 read_file 工具读取该引用获取完整内容");
        } else {
            sb.append("└─ 元数据: ").append(originalChars).append(" 字符");
        }

        block.setText(sb.toString());
    }
}
