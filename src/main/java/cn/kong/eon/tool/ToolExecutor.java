package cn.kong.eon.tool;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与工具上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具。
     *
     * @param arguments 模型传入的参数
     * @param runtime   本次调用的工具上下文（已含 turn / sessionId）
     */
    ToolOutcome execute(Map<String, Object> arguments, ToolRuntime runtime);

    /** 释放工具持有的资源，应用关闭时由 ToolService.closeAll() 统一调用。 */
    default void close() {
    }
}
