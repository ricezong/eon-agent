package cn.kong.eon.engine.hook;

import cn.kong.eon.runtime.RunContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * Hook 调度器。按阶段分组调度各 Hook，返回第一个 stop/skip 结果，全部通过则返回 null。
 */
public class HookDispatcher {

    /** 调度 PreModel Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPreModel(List<Hook.PreModelHook> hooks, RunContext r) {
        for (Hook.PreModelHook hook : hooks) {
            if (!hook.active(r)) {
                continue;
            }
            HookResult result = hook.beforeModelCall(r);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PostModel Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPostModel(List<Hook.PostModelHook> hooks, RunContext r) {
        for (Hook.PostModelHook hook : hooks) {
            if (!hook.active(r)) {
                continue;
            }
            HookResult result = hook.afterModelCall(r);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PreTool Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPreTool(List<Hook.PreToolHook> hooks, RunContext r,
                                             List<ToolExecutionRequest> requests) {
        for (Hook.PreToolHook hook : hooks) {
            if (!hook.active(r)) {
                continue;
            }
            HookResult result = hook.beforeToolExecution(r, requests);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }

    /** 调度 PostTool Hook。返回 stop/skip 结果，全部通过则返回 null。 */
    public static HookResult dispatchPostTool(List<Hook.PostToolHook> hooks, RunContext r,
                                              String toolName, boolean success) {
        for (Hook.PostToolHook hook : hooks) {
            if (!hook.active(r)) {
                continue;
            }
            HookResult result = hook.afterToolExecution(r, toolName, success);
            if (result.isSkip() || result.isStop()) {
                return result;
            }
        }
        return null;
    }
}
