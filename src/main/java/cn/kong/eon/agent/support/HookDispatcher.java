package cn.kong.eon.agent.support;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.SessionState;

import java.util.List;
import java.util.function.Function;

/**
 * Hook 调度返回值。三态 sealed interface，无 null 歧义。
 */
public class HookDispatcher {

    /**
     * Hook 调度统一返回值。三态枚举，无 null 歧义。
     */
    public sealed interface FireResult permits FireResult.Continue, FireResult.Skip, FireResult.Exit {
        /** 继续，一切正常。 */
        record Continue() implements FireResult {}

        /** 跳过后续步骤（stop 已 finalize）。 */
        record Skip() implements FireResult {}

        /** 退出整个循环，携带最终输出。 */
        record Exit(String output) implements FireResult {}

        /** 便捷判断：是否为 Exit。 */
        default boolean isExit() {
            return this instanceof Exit;
        }
    }

    /**
     * 调度 PreModel Hook。
     * <p>
     * 与其他阶段不同：stop 后不 finalize、不 skip，继续遍历后续 hook。
     * 原因：如 BudgetHook stop 后 ContextCompactHook 仍需执行。
     */
    public static FireResult dispatchPreModel(
            List<Hook.PreModelHook> hooks,
            SessionState state,
            ContextBuilder ctx,
            Function<StopReason, FireResult> stopHandler) {
        for (Hook.PreModelHook hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult result = hook.beforeModelCall(state, ctx);
            if (!result.isStop()) continue;
            FireResult stopResult = stopHandler.apply(result.getStopReason());
            if (stopResult.isExit()) return stopResult;
            // PreModel: stop 后继续遍历
        }
        return new FireResult.Continue();
    }

    /**
     * 调度 PostModel Hook。
     * <p>
     * stop 后 finalize + skip，跳过后续 hook。
     */
    public static FireResult dispatchPostModel(
            List<Hook.PostModelHook> hooks,
            SessionState state,
            cn.kong.eon.llm.LlmResponse response,
            java.util.function.Function<StopReason, FireResult> stopHandler,
            Runnable finalizePending) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult result = hook.afterModelCall(state, response);
            if (!result.isStop()) continue;
            FireResult stopResult = stopHandler.apply(result.getStopReason());
            if (stopResult.isExit()) return stopResult;
            finalizePending.run();
            return new FireResult.Skip();
        }
        return new FireResult.Continue();
    }

    /**
     * 调度 PreTool Hook。
     * <p>
     * stop 后 finalize + skip，跳过后续 hook。
     */
    public static FireResult dispatchPreTool(
            List<Hook.PreToolHook> hooks,
            SessionState state,
            List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests,
            java.util.function.Function<StopReason, FireResult> stopHandler,
            Runnable finalizePending) {
        for (Hook.PreToolHook hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult result = hook.beforeToolExecution(state, requests);
            if (!result.isStop()) continue;
            FireResult stopResult = stopHandler.apply(result.getStopReason());
            if (stopResult.isExit()) return stopResult;
            finalizePending.run();
            return new FireResult.Skip();
        }
        return new FireResult.Continue();
    }

    /**
     * 调度 PostTool Hook。
     * <p>
     * stop 后 finalize + skip，跳过后续 hook。
     */
    public static FireResult dispatchPostTool(
            List<Hook.PostToolHook> hooks,
            SessionState state,
            String toolName,
            boolean success,
            java.util.function.Function<StopReason, FireResult> stopHandler,
            Runnable finalizePending) {
        for (Hook.PostToolHook hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult result = hook.afterToolExecution(state, toolName, success);
            if (!result.isStop()) continue;
            FireResult stopResult = stopHandler.apply(result.getStopReason());
            if (stopResult.isExit()) return stopResult;
            finalizePending.run();
            return new FireResult.Skip();
        }
        return new FireResult.Continue();
    }
}
