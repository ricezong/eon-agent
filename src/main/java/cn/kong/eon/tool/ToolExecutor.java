package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与运行时上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * @param arguments 模型传入的参数（含 reason 字段）
     */
    ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context);

    /**
     * 提取工具调用的关键参数摘要，用于日志展示。
     * 默认实现返回参数 Map 的 toString 截断版本，工具可覆写以提供更有意义的摘要。
     */
    default String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        String s = args.toString();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    /**
     * 释放工具持有的资源（如 Scanner、文件句柄等）。
     * 默认空操作，由持有资源的工具覆写。
     * 在会话销毁时由 ToolRegistry.closeAll() 统一调用。
     */
    default void close() {
    }
}
