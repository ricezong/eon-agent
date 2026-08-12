package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;

/**
 * 能力模块接口。
 *
 * 能力模块是可插拔的横切关注点，按 Profile 激活。
 * 三个 Hook 点：
 * - beforeModelCall：模型调用前，可组装上下文、修改工具列表
 * - afterModelCall：模型调用后，可解析输出、决定是否进入扩展循环
 * - afterToolExecution：工具执行后，可更新失败计数、检查熔断
 *
 * 执行顺序按 priority 排序：
 * - HIGH(1)：前置守卫（如 BudgetGuard，先检查预算再压缩）
 * - NORMAL(2)：上下文处理（如 ContextCompactor、TodoNavigator）
 * - LOW(3)：后置记录（如 CheckpointManager）
 */
public interface CapabilityModule {

    /**
     * 模块名称（用于日志和调试）。
     */
    String name();

    /**
     * 是否激活（由当前状态和 Profile 决定）。
     */
    boolean isActive(SessionState state);

    /**
     * 优先级（决定执行顺序）。默认 NORMAL。
     */
    default int priority() { return Priority.NORMAL; }

    /**
     * 模型调用前：组装上下文、修改工具列表。
     */
    default void beforeModelCall(SessionState state, ContextBuilder ctx) {}

    /**
     * 模型调用后：解析输出、决定是否进入扩展循环。
     */
    default void afterModelCall(SessionState state, LlmResponse response) {}

    /**
     * 工具执行后：失败反馈、状态更新。
     */
    default void afterToolExecution(SessionState state, String toolName, boolean success) {}

    /**
     * 优先级常量。
     */
    final class Priority {
        public static final int HIGH = 1;
        public static final int NORMAL = 2;
        public static final int LOW = 3;
        private Priority() {}
    }
}
