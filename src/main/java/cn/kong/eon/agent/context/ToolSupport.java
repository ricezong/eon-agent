package cn.kong.eon.agent.context;

/**
 * 上下文层对工具层的依赖倒置接口。
 * context 包需要知道工具是否把调用参数完整持久化到磁盘（以及持久化到了哪里），
 * 但不应该因此依赖 tool 包的任何具体类；由 {@code ToolRegistry} 实现本接口并在装配期注入。
 */
public interface ToolSupport {

    /**
     * 工具是否会把它的调用参数完整持久化到磁盘。
     * 为 true 时，该工具调用成功的 TOOL_ARGS 块被标记为 recoverable，
     * 清空其内容不损失信息。
     */
    boolean persistsArgs(String toolName);

    /**
     * 该工具本次调用的参数被持久化到的位置（文件路径）。
     * 入站时盖在 TOOL_ARGS 块上，压缩骨架化据此生成可导航的占位说明。
     * 工具不持久化参数、参数 JSON 无法解析或无法确定唯一位置时返回 null。
     */
    String persistedLocation(String toolName, String argumentsJson);
}
