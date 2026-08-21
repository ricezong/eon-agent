package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.SessionState;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Hook 统一调度器。遍历 hooks → isActive → 调用 → 判断 isStop → handleStop。
 * PreModel 阶段 stop 后继续遍历后续 hook；其他阶段 stop 后 finalize + skip。
 * 返回 {@link FireResult}：EXIT/SKIP/CONTINUE，无 null 歧义。
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
        default boolean isExit() { return this instanceof Exit; }
    }

    /**
     * 调度 PreModel Hook。stop 后不 finalize、不 skip，继续遍历后续 hook。
     */
    public static <H extends Hook.PreModelHook> FireResult dispatchPreModel(
            List<H> hooks,
            SessionState state,
            BiFunction<H, SessionState, HookResult> invoker,
            Function<StopReason, FireResult> stopHandler) {
        for (H hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult r = invoker.apply(hook, state);
            if (!r.isStop()) continue;
            FireResult sr = stopHandler.apply(r.getStopReason());
            if (sr.isExit()) return sr;
            // PreModel: stop 后继续遍历（如 BudgetHook stop 后 ContextCompactHook 仍需执行）
        }
        return new FireResult.Continue();
    }

    /**
     * 调度 Hook（PostModel/PreTool/PostTool）。stop 后 finalize + skip，跳过后续 hook。
     */
    public static <H extends Hook> FireResult dispatch(
            List<H> hooks,
            SessionState state,
            BiFunction<H, SessionState, HookResult> invoker,
            Function<StopReason, FireResult> stopHandler,
            Runnable finalizePending) {
        for (H hook : hooks) {
            if (!hook.isActive(state)) continue;
            HookResult r = invoker.apply(hook, state);
            if (!r.isStop()) continue;
            FireResult sr = stopHandler.apply(r.getStopReason());
            if (sr.isExit()) return sr;
            // stop 已注入 nudge，finalize 后跳过后续 hook
            finalizePending.run();
            return new FireResult.Skip();
        }
        return new FireResult.Continue();
    }
}
