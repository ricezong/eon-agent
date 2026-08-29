package cn.kong.eon.agent.context.block;

/**
 * 工具元数据查询。投射层据此判定块的保留策略，避免 context 包反向依赖 tool 包。
 */
@FunctionalInterface
public interface ToolMetaLookup {

    /** 空实现：所有工具都视为参数未落盘（全部 COMPRESSIBLE）。 */
    ToolMetaLookup NONE = toolName -> false;

    /**
     * 工具是否会把它的调用参数完整持久化到磁盘。
     * <p>
     * 为 true 时，该工具的 {@link BlockKind#TOOL_ARGS} 块被标记为
     * {@link Retention#OFFLOADABLE}——因为磁盘上已有完整副本，
     * 上下文里那份是纯冗余，替换为"摘要 + 路径引用"不损失任何信息。
     */
    boolean persistsArguments(String toolName);
}
