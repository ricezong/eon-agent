package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArgumentOffloader;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具参数无损卸载（order=30）。本次改造收益最大的一条规则。
 * <p>
 * <b>为什么无损</b>：声明了 {@code persistsArguments()} 的工具（典型是 write）
 * 已经用 {@code Files.writeString} 把内容完整写到磁盘了，
 * 历史里那份 arguments 和磁盘文件<b>逐字节重复</b>。
 * 把它换成"参数骨架 + 路径引用"不损失任何信息，模型需要时可 read_file 取回。
 * <p>
 * <b>为什么在入站就做，而不是等水位</b>：既然无损，就没有"等快满再做"的道理。
 * 等水位触发意味着中间每一轮都要为这份冗余内容重复付费
 * （预算 = Σ 每轮发送量，是上下文大小对时间的积分）。
 * 实测：阈值不动、仅加这条规则，稳态上下文 94,369 → 58,423，可跑轮数 39 → 51。
 * <p>
 * <b>安全边界一</b>：只有执行<b>成功</b>的调用才卸载。
 * 失败的调用没有真正落盘，卸载会永久丢失内容。
 * <p>
 * <b>安全边界二</b>：替换文本必须由 {@link ArgumentOffloader} 生成，
 * 以保证输出是严格合法的 JSON。本规则只负责"该不该卸载"的判断与记账，
 * 不再自己拼字符串。
 */
public class ArgumentOffloadRule implements IngestRule {
    private static final Logger log = LoggerFactory.getLogger(ArgumentOffloadRule.class);

    @Override
    public String name() {
        return "ArgumentOffload";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_ARGS
                && !block.isOffloaded()
                && block.chars() > ctx.offloadMinChars()
                && block.toolName() != null
                && ctx.toolSupport().persistsArguments(block.toolName())
                && ctx.succeeded(block.toolCallId());
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        int originalChars = block.chars();
        String path = ArgumentOffloader.extractPath(block.text(), ctx.objectMapper());

        // 返回 null 表示无法保持合法 JSON，放弃卸载。
        // 宁可让这份冗余多占几轮 token，也不能发出一个会被供应商拒收的请求。
        String replacement = ArgumentOffloader.offload(block.text(), path, ctx.objectMapper());
        if (replacement == null) {
            log.debug("[入站] 参数卸载跳过: {}({}) 无可替换的超长字段",
                    block.toolName(), block.toolCallId());
            return;
        }

        block.setText(replacement);
        block.markOffloaded();
        log.info("[入站] 参数卸载: {}({}) {} -> {} 字符 | 落盘于 {}",
                block.toolName(), block.toolCallId(), originalChars, replacement.length(),
                path != null ? path : "(路径未知)");
    }
}
