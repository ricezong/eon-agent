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
 * 工具参数无损卸载（在站规则）。受保护区约束，与 Snip/Prune 一样
 * 只处置 cutoffTurn 之前的块。
 * <p>
 * <b>为什么无损</b>：声明了 {@code persistsArguments()} 的工具（典型是 write）
 * 已经用 {@code Files.writeString} 把内容完整写到磁盘了，
 * 历史里那份 arguments 和磁盘文件<b>逐字节重复</b>。
 * 把它换成"参数骨架 + 路径引用"不损失任何信息，模型需要时可 read_file 取回。
 * <p>
 * <b>为什么从入站移到在站</b>：入站卸载会在模型刚写入后立刻骨架化参数，
 * 导致下一轮模型看不到上一轮写了什么内容（只看到"内容已落盘至 X"），
 * 可能引发重复写入。移到在站后，保护区内的近期块不受影响，
 * 模型在近期对话中能看到完整的参数内容。
 * <p>
 * <b>安全边界一</b>：只有执行<b>成功</b>的调用才卸载（块上的 success 标记由
 * 入站 {@code ToolResultFormatRule} 设置）。
 * 失败的调用没有真正落盘，卸载会永久丢失内容。
 * <p>
 * <b>安全边界二</b>：替换文本必须由 {@link ArgumentOffloader} 生成，
 * 以保证输出是严格合法的 JSON——这段文本会作为历史工具调用的
 * {@code arguments} 原样回传给模型，供应商会校验其格式。
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

            // 返回 null 表示无法保持合法 JSON，放弃卸载。
            // 宁可让这份冗余多占几轮 token，也不能发出一个会被供应商拒收的请求。
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
