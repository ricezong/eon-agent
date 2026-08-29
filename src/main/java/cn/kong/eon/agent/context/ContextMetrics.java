package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;

import java.util.EnumMap;
import java.util.Map;

/**
 * 上下文度量。把"水位"从一个随手估算的数字，升级为可诊断、可被策略消费的一等对象。
 * <p>
 * 补齐原先 {@code ContextBuilder.estimateTokens()} 漏算的两块：
 * <ul>
 *   <li><b>工具 schema</b>：每轮都要随请求发送，9 个内置工具 + MCP 工具约数千 token，
 *       100 轮就是预算的 15%，过去完全不计入</li>
 *   <li><b>输出预留</b>：{@code max_tokens} 是向模型承诺的响应空间，同样占用窗口</li>
 * </ul>
 * <p>
 * 并提供两个过去需要写脚本翻 transcript 才能得到的量：
 * <ul>
 *   <li><b>byKind 构成分解</b>：一眼看出上下文被谁占满（本项目典型是 TOOL_ARGS 占 72%）</li>
 *   <li><b>预算投影</b>：按当前单轮成本，剩余预算还能跑几轮。
 *       这让"预算会不会先光"从事后发现变成<b>可预测</b>，是可被策略消费的输入</li>
 * </ul>
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

    private ContextMetrics(Builder b) {
        this.transcriptTokens = b.transcriptTokens;
        this.anchorTokens = b.anchorTokens;
        this.toolSchemaTokens = b.toolSchemaTokens;
        this.outputReserveTokens = b.outputReserveTokens;
        this.contextMaxTokens = b.contextMaxTokens;
        this.budgetUsedTokens = b.budgetUsedTokens;
        this.budgetMaxTokens = b.budgetMaxTokens;
        this.tokensByKind = new EnumMap<>(b.tokensByKind);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ═══════════════════ 水位 ═══════════════════

    /**
     * 上下文水位：本轮真实要发送的量占窗口的比例。
     * 口径 = transcript + 锚点层 + 工具 schema + 输出预留。
     */
    public double waterLevel() {
        if (contextMaxTokens <= 0) return 0.0;
        return Math.min(1.0, (double) sentTokens() / contextMaxTokens);
    }

    /** 单轮真实发送 token 数 */
    public long sentTokens() {
        return transcriptTokens + anchorTokens + toolSchemaTokens + outputReserveTokens;
    }

    /** transcript 自身占窗口的比例（不含锚点与固定开销） */
    public double transcriptRatio() {
        if (contextMaxTokens <= 0) return 0.0;
        return Math.min(1.0, (double) transcriptTokens / contextMaxTokens);
    }

    // ═══════════════════ 预算 ═══════════════════

    public double budgetRatio() {
        if (budgetMaxTokens <= 0) return 0.0;
        return (double) budgetUsedTokens / budgetMaxTokens;
    }

    public long budgetRemainingTokens() {
        return Math.max(0, budgetMaxTokens - budgetUsedTokens);
    }

    /**
     * 预算投影：按当前单轮成本，剩余预算还能支撑多少轮。
     * <p>
     * 这是"预算感知"策略的输入——水位不高但投影吃紧时，
     * 也应该触发<b>无损</b>卸载来降低单轮成本（而不是等水位涨上去做有损压缩）。
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

    // ═══════════════════ 快照 ═══════════════════

    public long transcriptTokens() {
        return transcriptTokens;
    }

    public long anchorTokens() {
        return anchorTokens;
    }

    public long toolSchemaTokens() {
        return toolSchemaTokens;
    }

    public long outputReserveTokens() {
        return outputReserveTokens;
    }

    public long contextMaxTokens() {
        return contextMaxTokens;
    }

    public long budgetUsedTokens() {
        return budgetUsedTokens;
    }

    public long budgetMaxTokens() {
        return budgetMaxTokens;
    }

    @Override
    public String toString() {
        return String.format(
                "ContextMetrics{水位=%.1f%% 发送=%,d/%,d 预算=%.1f%% 投影剩余=%.1f轮 构成=%s}",
                waterLevel() * 100, sentTokens(), contextMaxTokens,
                budgetRatio() * 100, projectedRemainingTurns(), composition());
    }

    public static final class Builder {
        private long transcriptTokens;
        private long anchorTokens;
        private long toolSchemaTokens;
        private long outputReserveTokens;
        private long contextMaxTokens;
        private long budgetUsedTokens;
        private long budgetMaxTokens;
        private final Map<BlockKind, Long> tokensByKind = new EnumMap<>(BlockKind.class);

        public Builder transcriptTokens(long v) {
            this.transcriptTokens = v;
            return this;
        }

        public Builder anchorTokens(long v) {
            this.anchorTokens = v;
            return this;
        }

        public Builder toolSchemaTokens(long v) {
            this.toolSchemaTokens = v;
            return this;
        }

        public Builder outputReserveTokens(long v) {
            this.outputReserveTokens = v;
            return this;
        }

        public Builder contextMaxTokens(long v) {
            this.contextMaxTokens = v;
            return this;
        }

        public Builder budgetUsedTokens(long v) {
            this.budgetUsedTokens = v;
            return this;
        }

        public Builder budgetMaxTokens(long v) {
            this.budgetMaxTokens = v;
            return this;
        }

        public Builder tokensByKind(Map<BlockKind, Long> v) {
            this.tokensByKind.clear();
            if (v != null) this.tokensByKind.putAll(v);
            return this;
        }

        public ContextMetrics build() {
            return new ContextMetrics(this);
        }
    }
}
