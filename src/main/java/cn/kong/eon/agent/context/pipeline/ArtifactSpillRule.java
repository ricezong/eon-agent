package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 大工具结果落盘（order=10）。
 * <p>
 * 原文超过阈值时完整落盘为 artifact，块里只留头尾摘要 + 引用。
 * 落盘的是<b>原始内容</b>，因此必须排在格式化规则之前。
 * <p>
 * 这条规则原先住在 {@code ToolResultRenderer} 里（工具执行层）。
 * 上移到入站管线的理由：控制"什么进入上下文、多大"是上下文边界的职责，
 * 放在工具层会导致工具结果有策略、工具参数没策略的不对称。
 * <p>
 * 阈值沿用原先的层层递进设计：
 * <pre>
 *   落盘阈值 = snipKeepChars × 3
 *   落盘摘要 = snipKeepChars × 2   （&gt; snipKeepChars，可被 Snip 二次截断）
 * </pre>
 */
public class ArtifactSpillRule implements IngestRule {
    private static final Logger log = LoggerFactory.getLogger(ArtifactSpillRule.class);

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
        int summaryChars = ctx.snipKeepChars() * 2;
        String summary = TextTrimmer.headTail(raw, summaryChars);

        ArtifactRef ref = ctx.artifactSink().save(
                block.toolName() != null ? block.toolName() : "tool", raw, summary);
        if (ref == null) {
            log.warn("[入站] artifact 落盘不可用，跳过 {}", block.toolName());
            return;
        }

        block.setText(summary);
        block.setRefId(ref.getRefId());
        block.markOffloaded();
        log.info("[入站] {} 落盘: {} ({} -> {} 字符)",
                block.toolName(), ref.getRefId(), raw.length(), summary.length());
    }
}
