package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.ArtifactSink;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.ToolMetaLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入站管线。所有内容进入上下文的<b>唯一关卡</b>。
 * <p>
 * 过去消息通过 {@code JsonlStore.append()} 直接落进上下文视图，
 * 工具结果的落盘策略却挂在工具执行层的渲染器里，
 * 于是"什么能进、多大能进"这件事没有任何统一把关点——
 * 工具参数因此完全裸奔（本项目 72% 的上下文就是这么来的）。
 * <p>
 * 管线把这件事收敛成一条有序规则链：新加一种处置方式 = 加一条规则，
 * 不需要改动调用方，也不会出现"某个新消息类型绕过策略"的漏洞。
 */
public class ContextPipeline {

    private final List<IngestRule> rules;
    private final ToolMetaLookup lookup;
    private final ArtifactSink artifactSink;
    private final ToolSupport toolSupport;
    private final ObjectMapper objectMapper;
    private final int snipKeepChars;
    private final int offloadMinChars;
    private final AtomicInteger groupSeq = new AtomicInteger(0);

    public ContextPipeline(List<IngestRule> rules,
                           ToolMetaLookup lookup,
                           ArtifactSink artifactSink,
                           ToolSupport toolSupport,
                           ObjectMapper objectMapper,
                           int snipKeepChars,
                           int offloadMinChars) {
        this.rules = new ArrayList<>(rules);
        this.rules.sort(Comparator.comparingInt(IngestRule::order));
        this.lookup = lookup != null ? lookup : ToolMetaLookup.NONE;
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NONE;
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.objectMapper = objectMapper;
        this.snipKeepChars = snipKeepChars;
        this.offloadMinChars = offloadMinChars;
    }

    /**
     * 消息入站：爆炸为块 → 依次应用规则 → 返回进入上下文的块。
     * <p>
     * 返回值同时是<b>进入上下文的视图</b>和<b>写入磁盘账本的内容</b>（由
     * {@code JsonlStore} 组装回消息后落盘）——二者同源，
     * 所以"账本里看到的"就是"模型当时看到的"。
     * 这一点很重要：若账本写原文，一条大工具结果会让单条记录膨胀到十几万字符，
     * "一条消息一行"的约定就名存实亡了。
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
        List<ContextBlock> blocks = BlockProjector.explode(msg, groupId, turn, lookup);
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
