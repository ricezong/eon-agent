package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.model.CompressionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文策略运行器。在 PreModel 阶段执行满足触发条件的压缩规则。
 * 规则按声明顺序执行，前一个规则处置后，后一个规则看到的是已更新的窗口状态。
 */
public class ContextPolicy {
    private static final Logger log = LoggerFactory.getLogger(ContextPolicy.class);

    private final List<ContextRule> rules;

    public ContextPolicy(List<ContextRule> rules) {
        this.rules = new ArrayList<>(rules);
    }

    /**
     * 依次执行满足触发条件的规则。
     *
     * @param window                 上下文窗口（就地修改）
     * @param metrics                当前度量
     * @param state                  压缩状态（摘要存放处）
     * @param turnsSinceLastCompress 距上次压缩的轮数
     * @param tailGuardTurns         尾部保护轮数
     */
    public PolicyResult runEligible(ContextWindow window,
                                    ContextMetrics metrics,
                                    CompressionState state,
                                    int turnsSinceLastCompress,
                                    int tailGuardTurns,
                                    int currentTurn) {
        if (window == null || window.isEmpty()) return PolicyResult.none();

        long charsBefore = window.totalChars();
        int cutoffTurn = window.cutoffTurn(tailGuardTurns);
        RuleContext ruleCtx = new RuleContext(window, metrics, state, cutoffTurn, currentTurn);

        List<String> stages = new ArrayList<>();

        for (ContextRule rule : rules) {
            if (!rule.shouldFire(metrics, turnsSinceLastCompress)) continue;

            PolicyResult outcome = rule.apply(ruleCtx);
            if (outcome.applied()) {
                stages.add(outcome.describe());
            }
        }

        if (stages.isEmpty()) return PolicyResult.none();

        // 压缩充分性：压完还降不下来就记录
        long charsAfter = window.totalChars();
        double reduction = charsBefore <= 0 ? 0.0 : (double) (charsBefore - charsAfter) / charsBefore;

        log.debug("[策略] 执行 {} | 降幅 {}%",
                String.join("+", stages), String.format("%.1f", reduction * 100));

        return new PolicyResult(true, stages, charsBefore, charsAfter);
    }
}
