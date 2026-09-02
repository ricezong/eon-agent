package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 入站规则。在消息进入上下文的那一刻对内容块做处置。
 */
public interface IngestRule {

    String name();

    /** 本规则是否作用于该块。 */
    boolean appliesTo(ContextBlock block, IngestContext ctx);

    /** 就地改写块内容。 */
    void apply(ContextBlock block, IngestContext ctx);
}
