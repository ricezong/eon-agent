package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ArgumentOffloader;
import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具参数无损卸载（运行时规则）。受尾部保护区约束，只处置 cutoffTurn 之前的块。
 * <p>
 * 声明了 persistsArguments() 的工具（如 write_file）已把内容完整写到磁盘，
 * 历史中的 arguments 与磁盘文件逐字节重复，替换为"骨架 + 路径引用"不损失任何信息。
 * <p>
 * 安全边界：只卸载执行成功的调用（失败调用未真正落盘），替换文本必须由
 * {@link ArgumentOffloader} 生成以保证严格合法的 JSON。
 */
public class ArgumentOffloadRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(ArgumentOffloadRule.class);

    private final double waterThreshold;
    private final int summarizeTurns;
    private final int offloadMinChars;
    private final ToolSupport toolSupport;
    private final ObjectMapper objectMapper;

    public ArgumentOffloadRule(double waterThreshold, int summarizeTurns,
                                int offloadMinChars,
                                ToolSupport toolSupport,
                                ObjectMapper objectMapper) {
        this.waterThreshold = waterThreshold;
        this.summarizeTurns = summarizeTurns;
        this.offloadMinChars = offloadMinChars;
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "ArgumentOffload";
    }

    @Override
    public boolean shouldFire(ContextMetrics metrics, int turnsSinceLastCompress) {
        return metrics.waterLevel() >= waterThreshold
                || turnsSinceLastCompress >= summarizeTurns;
    }

    @Override
    public PolicyResult apply(RuleContext ctx) {
        long before = ctx.window().totalChars();
        int count = 0;

        for (ContextBlock block : ctx.window().blocks()) {
            if (!eligible(block, ctx)) continue;

            int originalChars = block.chars();
            String path = ArgumentOffloader.extractPath(block.text(), objectMapper);

            // 返回 null 表示无法保持合法 JSON，放弃卸载
            String replacement = ArgumentOffloader.offload(block.text(), path, objectMapper);
            if (replacement == null) {
                log.debug("[在站] 参数卸载跳过: {}({}) 无可替换的超长字段",
                        block.toolName(), block.toolCallId());
                continue;
            }

            block.setText(replacement);
            block.markOffloaded();
            count++;
            log.info("[在站] 参数卸载: {}({}) {} -> {} 字符 | 落盘于 {}",
                    block.toolName(), block.toolCallId(), originalChars, replacement.length(),
                    path != null ? path : "(路径未知)");
        }

        long after = ctx.window().totalChars();
        if (count > 0) {
            log.info("[在站] 参数卸载: 处理 {} 个参数块 ({} -> {} 字符)", count, before, after);
        }
        return PolicyResult.of(count, before, after, "Offload×" + count);
    }

    private boolean eligible(ContextBlock block, RuleContext ctx) {
        return block.kind() == BlockKind.TOOL_ARGS
                && !block.isOffloaded()
                && block.chars() > offloadMinChars
                && block.toolName() != null
                && toolSupport.persistsArguments(block.toolName())
                && Boolean.TRUE.equals(block.success())
                && !block.isDisposed()
                && block.turn() < ctx.cutoffTurn();
    }
}
