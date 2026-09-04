package cn.kong.eon.agent.support;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 调度器。按阶段分组调度各 Hook，统一返回 {@link TurnOutcome}。
 * Hook 触发 stop 时立即终止循环并返回 Exit。
 */
public class HookDispatcher {

    /** 调度 PreModel Hook。stop 后立即返回 Exit。 */
    public static TurnOutcome dispatchPreModel(List<Hook.PreModelHook> hooks, SessionState state, ContextBuilder ctx, StopStateMachine stopStateMachine) {
        for (Hook.PreModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeModelCall(state, ctx);
            if (!result.isStop()) {
                continue;
            }
            StopReason reason = result.getStopReason();
            String output = stopStateMachine.forceTerminate(state, reason);
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PostModel Hook。stop 后立即返回 Exit。 */
    public static TurnOutcome dispatchPostModel(List<Hook.PostModelHook> hooks, SessionState state, StopStateMachine stopStateMachine) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterModelCall(state);
            if (!result.isStop()) {
                continue;
            }
            String output = stopStateMachine.forceTerminate(state, result.getStopReason());
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PreTool Hook。stop 后立即返回 Exit。 */
    public static TurnOutcome dispatchPreTool(List<Hook.PreToolHook> hooks, SessionState state, List<ToolExecutionRequest> requests, StopStateMachine stopStateMachine) {
        for (Hook.PreToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.beforeToolExecution(state, requests);
            if (!result.isStop()) {
                continue;
            }
            String output = stopStateMachine.forceTerminate(state, result.getStopReason());
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }

    /** 调度 PostTool Hook。stop 后立即返回 Exit。 */
    public static TurnOutcome dispatchPostTool(List<Hook.PostToolHook> hooks, SessionState state, String toolName, boolean success, StopStateMachine stopStateMachine) {
        for (Hook.PostToolHook hook : hooks) {
            if (!hook.active(state)) {
                continue;
            }
            HookResult result = hook.afterToolExecution(state, toolName, success);
            if (!result.isStop()) {
                continue;
            }
            String output = stopStateMachine.forceTerminate(state, result.getStopReason());
            return new TurnOutcome.Exit(output);
        }
        return new TurnOutcome.Continue();
    }
}
