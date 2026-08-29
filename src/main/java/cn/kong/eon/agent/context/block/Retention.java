package cn.kong.eon.agent.context.block;

/**
 * 内容块的保留策略。这是本项目的核心领域标签：
 * "什么能压、什么必须留"从控制流里的 instanceof 判断，变成数据上的声明。
 * <p>
 * 判定在投射层 {@link BlockProjector} 完成，规则只需声明自己处理哪种 Retention。
 */
public enum Retention {

    /**
     * 逐字保留。任何有损或无损规则都不得改写。
     * <p>
     * 覆盖：用户输入、系统提示词。丢弃这类内容会丢失措辞、隐含意图与行为约束。
     */
    VERBATIM,

    /**
     * 可无损卸载。磁盘上已有完整副本，替换为"摘要 + 路径引用"不损失任何信息，
     * 需要时可通过 read_file 取回。
     * <p>
     * 覆盖：声明了 {@code persistsArguments()} 的工具参数块、已落盘 artifact 的工具结果块。
     */
    OFFLOADABLE,

    /**
     * 仅有损压缩。磁盘上没有副本，是唯一一份，只能截断或摘要，会损失信息。
     * <p>
     * 覆盖：模型正文、未落盘的工具结果、未声明持久化的工具参数块。
     */
    COMPRESSIBLE;

    /**
     * 是否允许无损卸载。
     */
    public boolean offloadable() {
        return this == OFFLOADABLE;
    }

    /**
     * 是否允许有损改写（截断 / 占位符 / 删除）。
     */
    public boolean compressible() {
        return this == COMPRESSIBLE;
    }
}
