package cn.kong.eon.agent.context.block;

/**
 * 内容块的保留策略。声明该块是否允许被改写。
 * <p>
 * 判定在投射层 {@link BlockProjector} 一次性完成，处置层只读这个声明，
 * 不再对块类型或工具名做分支判断。
 * <p>
 * "磁盘上有没有副本"是与此正交的维度，由块上的 {@code recoverable} 标记表达，
 * 它决定替换是"无损（带引用）"还是"有损（丢内容）"，不影响能否改写。
 */
public enum Retention {

    /** 逐字保留。任何档位都不得改写。覆盖：用户输入、系统提示词。 */
    VERBATIM,

    /** 允许按档位改写内容。覆盖：模型正文、工具调用参数、工具结果。 */
    COMPRESSIBLE;

    /** 是否允许改写内容。 */
    public boolean compressible() {
        return this == COMPRESSIBLE;
    }
}
