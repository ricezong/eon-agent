package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ContentCompressor;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大载荷落盘（入站规则）。工具结果超过阈值时完整写入 artifact，块里只留头尾摘要 + 引用。
 * 落盘成功后该块即视为可恢复，后续档位清空其内容不损失信息。
 * 落盘不可用时保留原文，块按不可恢复处理。
 * <p>
 * <b>只作用于工具结果</b>：模型正文与用户输入不是"工具产生的外部数据"，
 * 它们的长度是上下文自身的组成部分，交给水位压缩按水位处置，不在这里提前卸载。
 * 工具参数同样不落盘——全项目只有 write 会产生大参数，而 write 本身就把内容写进了目标文件，
 * 磁盘上已有副本；参数的缩减是 PRUNE 档的裁剪逻辑（{@link ContentCompressor#skeleton}）。
 * <p>
 * <b>这里不做压缩</b>：落盘是"把内容搬到磁盘上以保证不丢"，与三档水位压缩是两套机制。
 * 块文本换成摘要只是落盘的固有动作（原文已进磁盘，窗口里没必要再留一份全量），
 * 不盖档位标记——后续水位压缩该截断照样截断、该清空照样清空。
 * <p>
 * <b>refId 由消息序号派生</b>：会话恢复的回放走同一条入站路径，同一消息再次命中本规则时
 * 落到同一个文件（同名同内容幂等覆盖），无需区分回放与常规写入，也不会与磁盘上已有
 * artifact 错位。见 {@code ArtifactStore}。
 */
public class ArtifactSpillRule implements IngestRule {
    private static final Logger log = LoggerFactory.getLogger(ArtifactSpillRule.class);

    private final ContentCompressor compressor;

    public ArtifactSpillRule(ContentCompressor compressor) {
        this.compressor = compressor;
    }

    @Override
    public String name() {
        return "ArtifactSpill";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT
                && block.chars() > ctx.spillThresholdChars();
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        String raw = block.text();
        String summary = compressor.headTail(raw, ctx.spillKeepChars());

        ArtifactRef ref = ctx.storeSupport().save(block.toolName(), raw, block.messageSeq());
        if (ref == null) {
            log.warn("[入站] artifact 落盘不可用，{} 保留原文入窗", block.toolName());
            block.setText(raw);
            return;
        }
        block.setRefId(ref.refId());
        block.setRecoverable(true);
        block.setText(summary);
        log.info("[入站] {} 落盘: {} ({} -> {} 字符)", block.toolName(), block.refId(), raw.length(), block.chars());
    }
}
