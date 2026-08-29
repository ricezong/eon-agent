package cn.kong.eon.agent.context.policy;

/**
 * 规则执行结果。
 */
public record RuleOutcome(int blocksAffected, long charsBefore, long charsAfter, String description) {

    public static RuleOutcome none() {
        return new RuleOutcome(0, 0, 0, "");
    }

    public static RuleOutcome of(int blocksAffected, long charsBefore, long charsAfter, String description) {
        return new RuleOutcome(blocksAffected, charsBefore, charsAfter, description);
    }

    public boolean applied() {
        return blocksAffected > 0;
    }

    public long charsSaved() {
        return Math.max(0, charsBefore - charsAfter);
    }

    /**
     * 降幅比例（相对执行前）。供"压缩充分性"判定使用。
     */
    public double reduction() {
        if (charsBefore <= 0) return 0.0;
        return (double) charsSaved() / charsBefore;
    }
}
