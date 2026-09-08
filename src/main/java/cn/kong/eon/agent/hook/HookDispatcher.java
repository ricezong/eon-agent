package cn.kong.eon.agent.hook;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.stop.StopFunction;
import cn.kong.eon.agent.turn.TurnOutcome;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 调度器。按阶段分组调度各 Hook，触发 stop 时立即返回 Exit。
 */
public class HookDispatcher {

    /** 调度 PreModel Hook。 */
    public static TurnOutcome dispatchPreModel(List<Hook.PreModelHook> hooks, SessionState state,
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
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PostModel Hook。 */
    public static TurnOutcome dispatchPostModel(List<Hook.PostModelHook> hooks, SessionState state,
                                                StopFunction stopFn) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterModelCall(state);
            if (!result.isStop()) {
                continue;
            }
            String output = stopFn.stop(state, result.getCategory(), result.getMessage());
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PreTool Hook。 */
    public static TurnOutcome dispatchPreTool(List<Hook.PreToolHook> hooks, SessionState state,
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
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PostTool Hook。 */
    public static TurnOutcome dispatchPostTool(List<Hook.PostToolHook> hooks, SessionState state,
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
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }
}
