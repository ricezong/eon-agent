package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大载荷落盘（入站规则）。
 * 原文超过阈值时完整落盘为 artifact，块里只留头尾摘要 + 引用。
 * 落盘成功后该块即视为可恢复，后续档位清空其内容不损失信息。
 * 落盘不可用时保留原文，块按不可恢复处理。
 * <p>
 * <b>不区分块类型</b>——工具结果、模型长输出、用户粘贴的文件一视同仁，
 * 判定只看字符数。被 pin 的块（当前用户输入）用高得多的阈值：它是当前诉求的唯一载体，
 * 落盘是最后手段而非常规优化。
 */
public class ArtifactSpillRule implements IngestRule {
    private static final Logger log = LoggerFactory.getLogger(ArtifactSpillRule.class);

    /** 落盘摘要长度相对 SNIP 档保留量的倍数：摘要比后续 SNIP 档保留得多，形成递进压缩。 */
    private static final int SUMMARY_MULTIPLIER = 2;
    /** 普通块的落盘阈值倍数。 */
    private static final int SPILL_MULTIPLIER = 3;
    /** 被 pin 块的落盘阈值倍数。当前诉求宁可多占 token 也要保持可读。 */
    private static final int PINNED_SPILL_MULTIPLIER = 10;

    @Override
    public String name() {
        return "ArtifactSpill";
    }

    @Override
    public boolean appliesTo(ContextBlock block, IngestContext ctx) {
        return block.chars() > threshold(block, ctx);
    }

    @Override
    public void apply(ContextBlock block, IngestContext ctx) {
        String raw = block.text();
        String summary = TextTrimmer.headTail(raw, ctx.snipKeepChars() * SUMMARY_MULTIPLIER);

        String owner = block.toolName() != null
                ? block.toolName()
                : block.kind().name().toLowerCase();
        ArtifactRef ref = ctx.storeSupport().save(owner, raw, summary);
        if (ref == null) {
            log.warn("[入站] artifact 落盘不可用，{} 保留原文入窗", owner);
            return;
        }

        block.setText(block.isPinned() ? pinnedSpillText(raw, summary, ref.getRefId()) : summary);
        block.setRefId(ref.getRefId());
        block.setRecoverable(true);
        log.info("[入站] {} 落盘: {} ({} -> {} 字符)",
                owner, ref.getRefId(), raw.length(), block.chars());
    }

    /** 本块的落盘阈值。 */
    private static int threshold(ContextBlock block, IngestContext ctx) {
        int multiplier = block.isPinned() ? PINNED_SPILL_MULTIPLIER : SPILL_MULTIPLIER;
        return ctx.snipKeepChars() * multiplier;
    }

    /**
     * 被 pin 块落盘后的文本。指令内嵌在块里而非写在系统提示词中——
     * 只有真正遇到超长输入时才需要这条指令，常驻提示词纯属浪费。
     */
    private static String pinnedSpillText(String raw, String summary, String refId) {
        return "[用户输入过长（" + raw.length() + " 字符），完整内容已保存至 artifact://" + refId + "。\n"
                + "请先用 read_file 分批读取完整内容，再汇总用户意图后开始工作。]\n"
                + summary;
    }
}
