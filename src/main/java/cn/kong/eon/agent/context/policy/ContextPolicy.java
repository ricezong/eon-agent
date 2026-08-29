package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.model.CompressionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文策略机。取代原先 ContextCompactHook 里 11 步的 if-else 编排。
 * <p>
 * 策略机只做两件事：
 * <ol>
 *   <li>遍历规则，问"你的触发条件满足了吗"</li>
 *   <li>收集执行结果</li>
 * </ol>
 * 它<b>不知道</b>有几种压缩、阈值是多少——全是规则自己的事。
 * 因此新增处置方式 = 加一个 {@link ContextRule} 实现类，策略机一行不改。
 */
public class ContextPolicy {
    private static final Logger log = LoggerFactory.getLogger(ContextPolicy.class);

    private final List<ContextRule> rules;
    private final double sufficiencyPct;

    public ContextPolicy(List<ContextRule> rules, double sufficiencyPct) {
        this.rules = new ArrayList<>(rules);
        this.sufficiencyPct = sufficiencyPct;
    }

    /**
     * 运行所有满足触发条件的规则。
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
        long totalCharsAfter = charsBefore;

        for (ContextRule rule : rules) {
            if (!rule.shouldFire(metrics, turnsSinceLastCompress)) continue;

            PolicyResult outcome = rule.apply(ruleCtx);
            if (outcome.applied()) {
                stages.add(outcome.describe());
                totalCharsAfter = window.totalChars();
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

    public List<ContextRule> rules() {
        return List.copyOf(rules);
    }
}
