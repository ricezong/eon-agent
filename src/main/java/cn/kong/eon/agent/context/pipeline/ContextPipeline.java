package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArtifactSink;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入站管线。所有内容进入上下文的唯一关卡。
 * 规则按列表声明顺序执行，新加一种处置方式 = 加一条规则，不需要改动调用方。
 */
public class ContextPipeline {

    private final List<IngestRule> rules;
    private final ArtifactSink artifactSink;
    private final ToolSupport toolSupport;
    private final int snipKeepChars;
    private final AtomicInteger groupSeq = new AtomicInteger(0);

    public ContextPipeline(List<IngestRule> rules,
                           ArtifactSink artifactSink,
                           ToolSupport toolSupport,
                           int snipKeepChars) {
        this.rules = new ArrayList<>(rules);
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NONE;
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.snipKeepChars = snipKeepChars;
    }

    /**
     * 消息入站：爆炸为块 → 依次应用规则 → 返回进入上下文的块。
     *
     * @param turn               入站轮次
     * @param succeededToolCalls 本轮执行成功的工具调用 id（可恢复性的判定依据）
     */
    public List<ContextBlock> ingest(ChatMessage msg, int turn, Set<String> succeededToolCalls) {
        IngestContext ctx = new IngestContext(
                artifactSink, toolSupport, snipKeepChars,
                succeededToolCalls != null ? succeededToolCalls : Collections.emptySet(),
                turn);

        String groupId = "g" + groupSeq.incrementAndGet();
        List<ContextBlock> blocks = BlockProjector.explode(msg, groupId, turn);
        for (ContextBlock block : blocks) {
            for (IngestRule rule : rules) {
                if (rule.appliesTo(block, ctx)) {
                    rule.apply(block, ctx);
                }
            }
        }
        return blocks;
    }
}
