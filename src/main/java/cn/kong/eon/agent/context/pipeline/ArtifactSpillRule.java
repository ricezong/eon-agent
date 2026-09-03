package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大工具结果落盘（入站规则）。
 * 原文超过阈值（snipKeepChars × 3）时完整落盘为 artifact，块里只留头尾摘要 + 引用。
 * 落盘成功后该块即视为可恢复，后续档位清空其内容不损失信息。
 * 落盘不可用时保留原文，块按不可恢复处理。
 */
public class ArtifactSpillRule implements IngestRule {
    private static final Logger log = LoggerFactory.getLogger(ArtifactSpillRule.class);

    /** 落盘摘要长度相对 SNIP 档保留量的倍数：摘要比后续 SNIP 档保留得多，形成递进压缩。 */
    private static final int SUMMARY_MULTIPLIER = 2;

    @Override
    public String name() {
        return "ArtifactSpill";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.kind() == BlockKind.TOOL_RESULT
                && block.chars() > ctx.snipKeepChars() * 3;
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        String raw = block.text();
        String summary = TextTrimmer.headTail(raw, ctx.snipKeepChars() * SUMMARY_MULTIPLIER);

        ArtifactRef ref = ctx.storeSupport().save(
                block.toolName() != null ? block.toolName() : "tool", raw, summary);
        if (ref == null) {
            log.warn("[入站] artifact 落盘不可用，{} 保留原文入窗", block.toolName());
            return;
        }

        block.setText(summary);
        block.setRefId(ref.getRefId());
        block.setRecoverable(true);
        log.info("[入站] {} 落盘: {} ({} -> {} 字符)",
                block.toolName(), ref.getRefId(), raw.length(), summary.length());
    }
}
