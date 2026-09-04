package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与运行时上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具。
     *
     * @param arguments 模型传入的参数
     */
    ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context);

    /**
     * 本工具是否会把调用参数完整持久化到磁盘。
     * 返回 true 时，上下文层会将该工具的 arguments 块标记为可无损卸载。
     * 典型场景：write 类工具的参数与磁盘文件逐字节重复。默认 false。
     */
    default boolean persistsArgs() {
        return false;
    }

    /**
     * 调用参数被持久化到的位置（文件路径）。
     * 入站时会盖在 TOOL_ARGS 块上，压缩骨架化时据此生成"去哪里取回"的指引，
     * 使占位文本不依赖对参数键名的猜测。
     * 参数中无路径或无法确定唯一位置时返回 null，占位文本退化为通用说明。
     * 默认 null；仅当 {@link #persistsArgs()} 为 true 时才会被调用。
     */
    default String persistedLocation(Map<String, Object> arguments) {
        return null;
    }

    /**
     * 释放工具持有的资源。默认空操作，由持有资源的工具覆写。
     * 在会话销毁时由 ToolRegistry.closeAll() 统一调用。
     */
    default void close() {
    }
}
