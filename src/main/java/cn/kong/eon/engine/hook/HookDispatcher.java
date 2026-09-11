package cn.kong.eon.engine.hook;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.runtime.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 调度器。按阶段分组调度各 Hook，返回第一个"非 ok"的结果：
 * <ul>
 *   <li>ok() → 继续调度下一个 Hook，全部 ok 时返回 {@code null}</li>
 *   <li>skip() → 立即返回该 skip 结果，由主循环决定如何跳过本轮后续阶段</li>
 *   <li>stop() → 立即返回该 stop 结果，由主循环调用 {@code StopHandler} 生成退出文本</li>
 * </ul>
 * <p>
 * 只做"找出结果"，不做终止动作——调度器不感知 {@code LoopAction} 与 {@code StopHandler}，
 * 从而切断 engine.hook → engine 的反向依赖。
 */
public class HookDispatcher {

    /** 调度 PreModel Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPreModel(List<Hook.PreModelHook> hooks, SessionState state,
                                              ContextBuilder ctx) {
        for (Hook.PreModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeModelCall(state, ctx);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PostModel Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPostModel(List<Hook.PostModelHook> hooks, SessionState state) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterModelCall(state);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PreTool Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPreTool(List<Hook.PreToolHook> hooks, SessionState state,
                                             List<ToolExecutionRequest> requests) {
        for (Hook.PreToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeToolExecution(state, requests);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PostTool Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPostTool(List<Hook.PostToolHook> hooks, SessionState state,
                                              String toolName, boolean success) {
        for (Hook.PostToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterToolExecution(state, toolName, success);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }
}
