package cn.kong.eon.agent.stop;

import cn.kong.eon.model.SessionState;

/**
 * 终止函数接口。HookDispatcher 依赖此接口而非具体 {@link StopHandler}，实现解耦。
 */
@FunctionalInterface
public interface StopFunction {
    String stop(SessionState state, StopCategory category, String message);
}
