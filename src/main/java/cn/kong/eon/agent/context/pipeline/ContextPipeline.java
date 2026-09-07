package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.StoreSupport;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 入站管线。所有内容进入上下文的唯一关卡。
 */
public class ContextPipeline {

    private final List<IngestRule> rules;
    private final StoreSupport storeSupport;
    private final int spillThresholdChars;
    private final int spillKeepChars;

    public ContextPipeline(List<IngestRule> rules,
                           StoreSupport storeSupport,
                           int spillThresholdChars,
                           int spillKeepChars) {
        this.rules = new ArrayList<>(rules);
        this.storeSupport = storeSupport;
        this.spillThresholdChars = spillThresholdChars;
        this.spillKeepChars = spillKeepChars;
    }

    /**
     * 消息入站：爆炸为块 → 依次应用规则 → 返回进入上下文的块。
     */
    public List<ContextBlock> ingest(ChatMessage msg, Set<String> succeededToolCalls, int messageSeq) {
        IngestContext ctx = new IngestContext(
                storeSupport,
                spillThresholdChars,
                spillKeepChars,
                succeededToolCalls != null ? succeededToolCalls : Collections.emptySet());

        // 消息分块
        List<ContextBlock> blocks = BlockProjector.explode(msg, messageSeq);
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
