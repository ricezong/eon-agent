package cn.kong.eon.agent.context;

/**
 * 上下文层对工具层的依赖倒置接口。
 * <p>
 * context 包需要知道两件关于工具的事，但不应该因此依赖 tool 包的任何具体类；
 * 由 {@code ToolRegistry} 实现本接口并在装配期注入。
 * <p>
 * 合并了原 ToolMetaLookup：投射层和入站管线都需要"参数是否落盘"这一条判据，
 * 不需要两个接口。
 */
public interface ToolSupport {

    /** 无工具可用时的实现：所有查询返回"未持久化"。 */
    ToolSupport NONE = new ToolSupport() {
        @Override
        public boolean persistsArguments(String toolName) {
            return false;
        }

        @Override
        public String summarizeArgs(String toolName, String argumentsJson) {
            return null;
        }
    };

    /**
     * 工具是否会把它的调用参数完整持久化到磁盘。
     * <p>
     * 为 true 时，该工具的 TOOL_ARGS 块被标记为 OFFLOADABLE——
     * 因为磁盘上已有完整副本，上下文里那份是纯冗余。
     */
    boolean persistsArguments(String toolName);

    /**
     * 工具参数的短摘要（如 {@code {path: "report.html"}}）。
     *
     * @return 摘要文本；无法解析或工具不存在时返回 null
     */
    String summarizeArgs(String toolName, String argumentsJson);
}
