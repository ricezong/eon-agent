package cn.kong.eon.context;

import cn.kong.eon.context.block.BlockKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * 上下文度量：水位与构成分解。
 */
public final class ContextMetrics {

    private final long transcriptTokens;
    private final long anchorTokens;
    private final long toolSchemaTokens;
    private final long outputReserveTokens;
    private final long contextMaxTokens;
    private final Map<BlockKind, Long> tokensByKind;

    public ContextMetrics(long transcriptTokens,
                          long anchorTokens,
                          long toolSchemaTokens,
                          long outputReserveTokens,
                          long contextMaxTokens,
                          Map<BlockKind, Long> tokensByKind) {
        this.transcriptTokens = transcriptTokens;
        this.anchorTokens = anchorTokens;
        this.toolSchemaTokens = toolSchemaTokens;
        this.outputReserveTokens = outputReserveTokens;
        this.contextMaxTokens = contextMaxTokens;
        Map<BlockKind, Long> map = new EnumMap<>(BlockKind.class);
        if (tokensByKind != null) map.putAll(tokensByKind);
        this.tokensByKind = map;
    }

    /** 上下文水位。 */
    public double waterLevel() {
        if (contextMaxTokens <= 0) return 0.0;
        return Math.min(1.0, (double) sentTokens() / contextMaxTokens);
    }

    /** 单轮真实发送 token 数。 */
    public long sentTokens() {
        return transcriptTokens + anchorTokens + toolSchemaTokens + outputReserveTokens;
    }

    /** 构成分解的可读形式。 */
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
}
