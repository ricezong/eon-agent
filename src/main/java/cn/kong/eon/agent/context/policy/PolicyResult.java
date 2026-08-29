package cn.kong.eon.agent.context.policy;

import java.util.List;

/**
 * 策略机一次运行的结果。
 */
public record PolicyResult(
        boolean applied,
        List<String> stages,
        List<String> reasons,
        long charsBefore,
        long charsAfter,
        int escalateLevel
) {

    public static PolicyResult none() {
        return new PolicyResult(false, List.of(), List.of(), 0, 0, 0);
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
}
