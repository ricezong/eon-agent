package cn.kong.eon.agent.hook;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.stop.StopFunction;
import cn.kong.eon.agent.LoopAction;
import cn.kong.eon.session.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 调度器。按阶段分组调度各 Hook：
 * <ul>
 *   <li>ok() → 继续执行下一个 Hook</li>
 *   <li>skip() → 跳过当前 Turn 后续阶段，直接返回 Continue</li>
 *   <li>stop() → 立即返回 Exit</li>
 * </ul>
 */
public class HookDispatcher {

    /** 调度 PreModel Hook。 */
    public static LoopAction dispatchPreModel(List<Hook.PreModelHook> hooks, SessionState state,
                                              ContextBuilder ctx, StopFunction stopFn) {
        for (Hook.PreModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeModelCall(state, ctx);
            if (!result.isStop()) {
                continue;
            }
            String output = stopFn.stop(state, result.getCategory(), result.getMessage());
            return new LoopAction.Exit(output);
        }
        return new LoopAction.Continue();
    }

    /** 调度 PostModel Hook。 */
    public static LoopAction dispatchPostModel(List<Hook.PostModelHook> hooks, SessionState state,
                                               StopFunction stopFn) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterModelCall(state);
            if (result.isSkip()) {
                return new LoopAction.Skip();
            }
            if (!result.isStop()) {
                continue;
            }
            String output = stopFn.stop(state, result.getCategory(), result.getMessage());
            return new LoopAction.Exit(output);
        }
        return new LoopAction.Continue();
    }

    /** 调度 PreTool Hook。 */
    public static LoopAction dispatchPreTool(List<Hook.PreToolHook> hooks, SessionState state,
                                             List<ToolExecutionRequest> requests, StopFunction stopFn) {
        for (Hook.PreToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeToolExecution(state, requests);
            if (!result.isStop()) {
                continue;
            }
            String output = stopFn.stop(state, result.getCategory(), result.getMessage());
            return new LoopAction.Exit(output);
        }
        return new LoopAction.Continue();
    }

    /** 调度 PostTool Hook。 */
    public static LoopAction dispatchPostTool(List<Hook.PostToolHook> hooks, SessionState state,
                                              String toolName, boolean success, StopFunction stopFn) {
        for (Hook.PostToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterToolExecution(state, toolName, success);
            if (!result.isStop()) {
                continue;
            }
            String output = stopFn.stop(state, result.getCategory(), result.getMessage());
            return new LoopAction.Exit(output);
        }
        return new LoopAction.Continue();
    }
}
