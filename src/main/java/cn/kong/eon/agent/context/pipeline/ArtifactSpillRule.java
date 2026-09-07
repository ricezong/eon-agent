package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ContentCompressor;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大载荷落盘（入站规则）。工具结果超阈值时完整写入 artifact，块里只留头尾摘要 + 引用。
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
