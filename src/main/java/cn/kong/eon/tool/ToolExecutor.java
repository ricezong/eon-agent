package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与运行时上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * @param arguments  模型传入的参数（含 reason 字段）
     * @param state      当前会话状态
     * @param context    工具执行上下文
     * @return           执行结果
     */
    ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context);
}
