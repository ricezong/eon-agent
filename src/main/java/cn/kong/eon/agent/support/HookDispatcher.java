package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.model.SessionState;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Hook 统一调度器。用一个泛型方法替换 EonAgent 中 4 个几乎相同的 fire*Hooks 方法。
 *
 * 调度逻辑统一为：
 *   遍历 hooks → isActive 检查 → 调用 hook → 判断 isStop → handleStop
 *
 * 两种 stop 策略：
 *   - continueAfterStop=false（默认）：stop 后 finalize + skip，跳过后续 hook
 *   - continueAfterStop=true：stop 后不 finalize、不 skip，继续遍历后续 hook
 *     （PreModel 场景：BudgetHook stop 后，NudgeRenderHook/ContextCompactHook 仍需执行）
 *
 * 返回 {@link FireResult}：exit（退出循环）/ skip（跳过后续，stop 已 finalize）/ cont（继续）。
 */
public class HookDispatcher {

    /**
     * Hook 调度统一返回值。
     * @param exitResult 非 null 表示应退出整个循环
     * @param skipped true 表示 stop 已 finalize，需跳过后续步骤
     */
    public record FireResult(String exitResult, boolean skipped) {
        public static FireResult cont() { return new FireResult(null, false); }
        public static FireResult exit(String r) { return new FireResult(r, false); }
        public static FireResult skip() { return new FireResult(null, true); }
    }

    /**
     * 调度 Hook 列表（stop 后 skip，跳过后续 hook）。
     * 适用于 PostModel / PreTool / PostTool。
     */
    public static <H extends Hook> FireResult dispatch(
            List<H> hooks,
            SessionState state,
            BiFunction<H, SessionState, HookResult> invoker,
            Function<StopReason, String> stopHandler,
            Runnable finalizePending) {
        return dispatch(hooks, state, invoker, stopHandler, finalizePending, false);
    }

    /**
     * 调度 Hook 列表（可配置 stop 后是否继续遍历）。
     *
     * @param continueAfterStop true=stop 后继续遍历后续 hook（PreModel 场景）；
     *                          false=stop 后 finalize + skip（其他场景）
     */
    public static <H extends Hook> FireResult dispatch(
            List<H> hooks,
            SessionState state,
            BiFunction<H, SessionState, HookResult> invoker,
            Function<StopReason, String> stopHandler,
            Runnable finalizePending,
            boolean continueAfterStop) {

        for (H hook : hooks) {
            if (!hook.isActive(state)) continue;

            HookResult r = invoker.apply(hook, state);
            if (!r.isStop()) continue;

            String stopResult = stopHandler.apply(r.getStopReason());
            if (stopResult != null) return FireResult.exit(stopResult);

            if (continueAfterStop) {
                // stop 已注入 nudge，继续遍历让后续 hook 执行（如渲染 nudge / 压缩上下文）
                continue;
            }

            // stop 已注入 nudge，finalize 后跳过后续 hook
            finalizePending.run();
            return FireResult.skip();
        }
        return FireResult.cont();
    }
}
