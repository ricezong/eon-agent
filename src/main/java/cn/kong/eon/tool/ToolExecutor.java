package cn.kong.eon.tool;

import cn.kong.eon.session.SessionState;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与运行时上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具。
     * @param arguments 模型传入的参数
     */
    ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context);

    /** 释放工具持有的资源，会话销毁时由 ToolService.closeAll() 统一调用。 */
    default void close() {
    }
}
