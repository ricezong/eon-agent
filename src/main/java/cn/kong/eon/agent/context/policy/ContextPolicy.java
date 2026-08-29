package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.model.CompressionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文策略机。取代原先 {@code ContextCompactHook} 里 11 步的 if-else 编排。
 * <p>
 * 策略机只做三件事：
 * <ol>
 *   <li>按级别顺序（无损 → Snip → Prune → Summarize）遍历规则</li>
 *   <li>问每个规则"你的触发条件满足了吗"（条件由规则自带，策略机不知道细节）</li>
 *   <li>判定压缩充分性，不够就升级档位</li>
 * </ol>
 * 它<b>不知道</b>有几种压缩、阈值是多少、轮数节奏如何——全是规则自己的事。
 * 因此新增处置方式 = 加一个 {@link ContextRule} 实现类，策略机一行不改。
 */
public class ContextPolicy {
    private static final Logger log = LoggerFactory.getLogger(ContextPolicy.class);

    private final List<ContextRule> rules;
    private final double sufficiencyPct;
    /** 轮数触发的当前档位：0=只跑 Snip，1=可跑 Prune，2=可跑 Summarize */
    private int escalateLevel = 0;

    public ContextPolicy(List<ContextRule> rules, double sufficiencyPct) {
        this.rules = new ArrayList<>(rules);
        this.rules.sort(Comparator.comparingInt(ContextRule::level));
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
        List<String> reasons = new ArrayList<>();

        for (ContextRule rule : rules) {
            String reason = matchReason(rule, metrics, turnsSinceLastCompress);
            if (reason == null) continue;

            RuleOutcome outcome = rule.apply(ruleCtx);
            if (outcome.applied()) {
                stages.add(outcome.description());
                reasons.add(rule.name() + " ← " + reason);
            }
        }

        if (stages.isEmpty()) return PolicyResult.none();

        // 压缩充分性：压完还降不下来就升级档位（对齐 WorkBuddy 的 COMPACT_SUFFICIENCY 思路）
        long charsAfter = window.totalChars();
        double reduction = charsBefore <= 0 ? 0.0 : (double) (charsBefore - charsAfter) / charsBefore;
        if (reduction < sufficiencyPct) {
            escalateLevel = Math.min(ContextRule.LEVEL_SUMMARIZE, escalateLevel + 1);
        } else {
            escalateLevel = 0;
        }

        log.debug("[策略] 执行 {} | 降幅 {}% | 档位 → {}",
                String.join("+", stages), String.format("%.1f", reduction * 100), escalateLevel);

        return new PolicyResult(true, stages, reasons, charsBefore, charsAfter, escalateLevel);
    }

    /**
     * 返回触发原因；未满足任何条件返回 null。
     */
    private String matchReason(ContextRule rule, ContextMetrics metrics, int turnsSinceLastCompress) {
        for (Trigger trigger : rule.triggers()) {
            if (trigger instanceof Trigger.WaterLevel w) {
                if (metrics.waterLevel() >= w.threshold()) {
                    return String.format("水位 %.0f%% ≥ %.0f%%", metrics.waterLevel() * 100, w.threshold() * 100);
                }
            } else if (trigger instanceof Trigger.TurnInterval ti) {
                int need = ti.baseTurns() * ti.multiplier();
                if (turnsSinceLastCompress >= need && rule.level() <= escalateLevel) {
                    return String.format("距上次压缩 %d 轮 ≥ %d 轮（档位 %d）",
                            turnsSinceLastCompress, need, escalateLevel);
                }
            } else if (trigger instanceof Trigger.BudgetProjection bp) {
                double remaining = metrics.projectedRemainingTurns();
                if (remaining < bp.minRemainingTurns()) {
                    return String.format("预算投影剩余 %.1f 轮 < %.1f 轮", remaining, bp.minRemainingTurns());
                }
            }
        }
        return null;
    }

    public int getEscalateLevel() {
        return escalateLevel;
    }

    public List<ContextRule> rules() {
        return List.copyOf(rules);
    }
}
