package cn.kong.eon.agent.context.policy;

import java.util.List;

/**
 * 策略机一次运行的结果，也是单条规则的执行结果。
 * 合并了原 RuleOutcome 和 PolicyResult 两个类型。
 */
public record PolicyResult(
        boolean applied,
        List<String> stages,
        long charsBefore,
        long charsAfter
) {

    public static PolicyResult none() {
        return new PolicyResult(false, List.of(), 0, 0);
    }

    public static PolicyResult of(int blocksAffected, long charsBefore, long charsAfter, String description) {
        return new PolicyResult(blocksAffected > 0, List.of(description), charsBefore, charsAfter);
    }

    public long charsSaved() {
        return Math.max(0, charsBefore - charsAfter);
    }

    /** 本轮压缩的降幅比例 */
    public double reduction() {
        if (charsBefore <= 0) return 0.0;
        return (double) charsSaved() / charsBefore;
    }

    public String describe() {
        return String.join("+", stages);
    }

    /**
     * 供策略机内部使用：检查单条规则是否产生了效果。
     */
    public boolean applied() {
        return applied;
    }
}
