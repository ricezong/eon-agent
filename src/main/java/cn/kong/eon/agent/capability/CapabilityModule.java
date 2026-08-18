package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * 能力模块接口。可插拔的横切关注点，按 Layer 分层、orderInLayer 微调顺序。
 * EonAgent 在 4 个 Hook 点统一调度所有激活的能力模块：
 *
 *   1. beforeModelCall      — 组装上下文、修改工具列表（预算检查、压缩、nudge 渲染等）
 *   2. afterModelCall       — 解析输出、决定是否进入扩展循环（循环检测等）
 *   3. beforeToolExecution  — 校验工具调用（门禁校验等）
 *   4. afterToolExecution    — 更新失败计数、保存快照（熔断、Todo 激活、Checkpoint 等）
 *
 * 排序：跨层 GUARD(1)→CONTEXT(2)→RENDER(3)→OBSERVE(4)→RECORD(5)，同层按 orderInLayer 升序。
 * 中断：返回 CapabilityResult.abort() 替代抛异常，与引擎解耦。
 */
public interface CapabilityModule {

    /** 模块名称（用于日志）。 */
    String name();

    /** 是否激活。 */
    boolean isActive(SessionState state);

    /** 职责层，决定跨层执行顺序。 */
    Layer layer();

    /** 同层内顺序，数值小先执行。默认 100。 */
    default int orderInLayer() { return 100; }

    /** Hook 1：模型调用前。 */
    default CapabilityResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        return CapabilityResult.ok();
    }

    /** Hook 2：模型调用后。 */
    default CapabilityResult afterModelCall(SessionState state, LlmResponse response) {
        return CapabilityResult.ok();
    }

    /** Hook 3：工具执行前。 */
    default CapabilityResult beforeToolExecution(SessionState state, List<ToolExecutionRequest> requests) {
        return CapabilityResult.ok();
    }

    /** Hook 4：工具执行后。 */
    default CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        return CapabilityResult.ok();
    }
}
