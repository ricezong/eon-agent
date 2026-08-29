package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * 上下文度量。水位、预算投影、构成分解。
 * <p>
 * 被 ContextPolicy（触发规则）和 ContextCompactHook（日志输出）消费。
 */
public final class ContextMetrics {

    private final long transcriptTokens;
    private final long anchorTokens;
    private final long toolSchemaTokens;
    private final long outputReserveTokens;
    private final long contextMaxTokens;
    private final long budgetUsedTokens;
    private final long budgetMaxTokens;
    private final Map<BlockKind, Long> tokensByKind;

    public ContextMetrics(long transcriptTokens, long anchorTokens,
                          long toolSchemaTokens, long outputReserveTokens,
                          long contextMaxTokens,
                          long budgetUsedTokens, long budgetMaxTokens,
                          Map<BlockKind, Long> tokensByKind) {
        this.transcriptTokens = transcriptTokens;
        this.anchorTokens = anchorTokens;
        this.toolSchemaTokens = toolSchemaTokens;
        this.outputReserveTokens = outputReserveTokens;
        this.contextMaxTokens = contextMaxTokens;
        this.budgetUsedTokens = budgetUsedTokens;
        this.budgetMaxTokens = budgetMaxTokens;
        Map<BlockKind, Long> map = new EnumMap<>(BlockKind.class);
        if (tokensByKind != null) map.putAll(tokensByKind);
        this.tokensByKind = map;
    }

    // ═══════════════════ 水位 ═══════════════════

    /**
     * 上下文水位：本轮真实要发送的量占窗口的比例。
     */
    public double waterLevel() {
        if (contextMaxTokens <= 0) return 0.0;
        return Math.min(1.0, (double) sentTokens() / contextMaxTokens);
    }

    /** 单轮真实发送 token 数 */
    public long sentTokens() {
        return transcriptTokens + anchorTokens + toolSchemaTokens + outputReserveTokens;
    }

    // ═══════════════════ 预算 ═══════════════════

    public long budgetRemainingTokens() {
        return Math.max(0, budgetMaxTokens - budgetUsedTokens);
    }

    /**
     * 预算投影：按当前单轮成本，剩余预算还能支撑多少轮。
     */
    public double projectedRemainingTurns() {
        long perTurn = sentTokens();
        if (perTurn <= 0) return Double.MAX_VALUE;
        return (double) budgetRemainingTokens() / perTurn;
    }

    // ═══════════════════ 构成 ═══════════════════

    public Map<BlockKind, Long> tokensByKind() {
        return tokensByKind;
    }

    /**
     * 构成分解的可读形式，例如 {@code TOOL_ARGS 72% | TOOL_RESULT 26% | AI_TEXT 2%}。
     */
    public String composition() {
        final long total = tokensByKind.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return "(空)";

        StringBuilder sb = new StringBuilder(64);
        tokensByKind.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(e.getKey().name())
                            .append(' ')
                            .append(Math.round((double) e.getValue() / total * 100))
                            .append('%');
                });
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(
                "ContextMetrics{水位=%.1f%% 发送=%,d/%,d 预算投影剩余=%.1f轮 构成=%s}",
                waterLevel() * 100, sentTokens(), contextMaxTokens,
                projectedRemainingTurns(), composition());
    }
}
