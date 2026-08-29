package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArtifactSink;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入站管线。所有内容进入上下文的<b>唯一关卡</b>。
 * <p>
 * 管线把"什么能进、多大能进"收敛成一条有序规则链：新加一种处置方式 = 加一条规则，
 * 不需要改动调用方，也不会出现"某个新消息类型绕过策略"的漏洞。
 * 规则按列表声明顺序执行。
 */
public class ContextPipeline {

    private final List<IngestRule> rules;
    private final ToolSupport toolSupport;
    private final ArtifactSink artifactSink;
    private final ObjectMapper objectMapper;
    private final int snipKeepChars;
    private final int offloadMinChars;
    private final AtomicInteger groupSeq = new AtomicInteger(0);

    public ContextPipeline(List<IngestRule> rules,
                           ArtifactSink artifactSink,
                           ToolSupport toolSupport,
                           ObjectMapper objectMapper,
                           int snipKeepChars,
                           int offloadMinChars) {
        this.rules = new ArrayList<>(rules);
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NONE;
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.objectMapper = objectMapper;
        this.snipKeepChars = snipKeepChars;
        this.offloadMinChars = offloadMinChars;
    }

    /**
     * 消息入站：爆炸为块 → 依次应用规则 → 返回进入上下文的块。
     *
     * @param turn               入站轮次
     * @param succeededToolCalls 本轮执行成功的工具调用 id（卸载的安全边界）
     */
    public List<ContextBlock> ingest(ChatMessage msg, int turn, Set<String> succeededToolCalls) {
        IngestContext ctx = new IngestContext(
                artifactSink, toolSupport, objectMapper,
                snipKeepChars, offloadMinChars,
                succeededToolCalls != null ? succeededToolCalls : Collections.emptySet(),
                turn);

        String groupId = "g" + groupSeq.incrementAndGet();
        List<ContextBlock> blocks = BlockProjector.explode(msg, groupId, turn, toolSupport);
        for (ContextBlock block : blocks) {
            for (IngestRule rule : rules) {
                if (rule.appliesTo(block, ctx)) {
                    rule.apply(block, ctx);
                }
            }
        }
        return blocks;
    }

    public List<IngestRule> rules() {
        return List.copyOf(rules);
    }
}
