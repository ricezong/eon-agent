package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.agent.context.policy.ContextPolicy;
import cn.kong.eon.agent.context.policy.PolicyResult;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文策略执行点（PreModel, order=100）。
 * <p>
 * 这里<b>没有任何压缩逻辑</b>——只有一次调用：
 * <pre>
 *     policy.runEligible(window, metrics, state, ...)
 * </pre>
 * "有几种处置方式、各自的触发阈值是多少"全部由 {@link ContextRule} 自己声明，
 * 策略机只回答"谁该跑"。因此新增一种处置 = 加一个规则实现类，本类一行不改。
 * <p>
 * 改造前这里是 11 步 if-else 编排，其中第 118 行的
 * {@code effectiveWaterLevel = waterTriggered ? waterLevel : snipThreshold}
 * 是最典型的症状：想表达"按轮数压"的意图，却只能伪造一个水位值来复用现成代码路径，
 * 代价是轮数触发被永久钉死在 Snip 级。现在轮数、水位、预算投影都是一等触发类型。
 */
public class ContextCompactHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactHook.class);

    private final AgentConfig config;
    private final ContextPolicy policy;

    public ContextCompactHook(AgentConfig config, ContextPolicy policy) {
        this.config = config;
        this.policy = policy;
    }

    @Override
    public String name() {
        return "ContextCompact";
    }

    /**
     * 始终激活。上下文策略是每次模型调用前的必要检查。
     */
    @Override
    public boolean isActive(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        ContextWindow window = ctx.getWindow();
        if (window == null || window.isEmpty()) return HookResult.ok();

        CompressionState cs = state.getCompressionState();
        int tailGuardTurns = config.getContext().getTailGuardMinTurns();
        int turnsSinceLastCompress = state.getTurnCount() - cs.getLastTurnCompressed();
        int blocksBefore = window.size();

        PolicyResult result = policy.runEligible(
                window, ctx.metrics(state), cs, turnsSinceLastCompress,
                tailGuardTurns, state.getTurnCount());

        // 处置后窗口变了，度量要重算：水位下降应反映到本轮日志与下一轮的触发判定
        var metricsAfter = ctx.metrics(state);
        cs.setLastWaterLevel(metricsAfter.waterLevel());

        if (!result.applied()) {
            return HookResult.ok();
        }

        // 删除块会切断 tool_use / tool_result 配对，LLM API 对此零容忍。
        // 这是窗口的结构不变式，由窗口自己维护，而不是调用点记得手动调一次。
        window.repairPairing();
        cs.setLastTurnCompressed(state.getTurnCount());

        log.info("[上下文] {} | {} -> {} 块 | {} -> {} 字符 (降幅 {}) | 水位 {} | 投影剩余 {} 轮 | 构成 {}",
                String.join(" + ", result.reasons()),
                blocksBefore, window.size(),
                result.charsBefore(), result.charsAfter(), pct(result.reduction()),
                pct(metricsAfter.waterLevel()),
                String.format("%.1f", metricsAfter.projectedRemainingTurns()),
                metricsAfter.composition());

        return HookResult.ok();
    }

    private static String pct(double ratio) {
        return String.format("%.0f%%", ratio * 100);
    }
}
