package cn.kong.eon.agent.context;

/**
 * 上下文层对工具层的依赖倒置接口。
 * context 包需要知道工具是否把调用参数完整持久化到磁盘，但不应该因此依赖 tool 包的任何具体类；
 * 由 {@code ToolRegistry} 实现本接口并在装配期注入。
 */
@FunctionalInterface
public interface ToolSupport {

    /** 无工具可用时的实现。 */
    ToolSupport NONE = toolName -> false;

    /**
     * 工具是否会把它的调用参数完整持久化到磁盘。
     * 为 true 时，该工具的 TOOL_ARGS 块被标记为 OFFLOADABLE。
     */
    boolean persistsArguments(String toolName);
}
