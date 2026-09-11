package cn.kong.eon.context.policy;

import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.context.ContextMetrics;
import cn.kong.eon.context.ContextWindow;
import cn.kong.eon.context.block.CompressionLevel;
import cn.kong.eon.context.block.ContextBlock;
import cn.kong.eon.context.CompressionState;
import cn.kong.eon.context.summary.ContextSummarizer;

import java.util.List;

/**
 * 压缩策略编排者。每轮在 PreModel 阶段执行：判档位 → 处置 → 修复配对 → 返回档位。
 * SNIP/PRUNE 就地处置块，SUMMARIZE 委托摘要器生成摘要后删除原文。
 */
public class CompressionPolicy {

    private final CompressionSettings settings;
    private final ContentCompressor compressor;
    private final ContextSummarizer summarizer;

    public CompressionPolicy(CompressionSettings settings,
                             ContentCompressor compressor,
                             ContextSummarizer summarizer) {
        this.settings = settings;
        this.compressor = compressor;
        this.summarizer = summarizer;
    }

    /**
     * 执行本轮压缩。
     *
     * @param transcriptPath 会话账本路径，仅在 SUMMARIZE 档透传给摘要器（用于兜底文案与提示词）
     */
    public CompressionLevel apply(ContextWindow window, ContextMetrics metrics,
                                  CompressionState state, int turnCount, String transcriptPath) {
        CompressionLevel level = resolveLevel(metrics, turnCount);
        if (!level.enabled()) {
            return CompressionLevel.NONE;
        }

        // 尾部保护区
        int protectedFrom = window.protectedFrom(settings.tailGuardBlocks());

        boolean disposed;
        if (level == CompressionLevel.SUMMARIZE) {
            String summary = summarizer.summarize(window, protectedFrom, state.getLastSummary(), transcriptPath);
            if (summary == null) {
                return CompressionLevel.NONE;
            }
            state.setLastSummary(summary);
            window.removeBefore(protectedFrom);
            // 修复配对后再取水位线：repairPairing 可能丢弃首块（孤立 TOOL_RESULT）
            window.repairPairing();
            int replayFrom = window.firstSurvivorSeq();
            // 摘要覆盖 #0~replayFrom-1，账本保留 #replayFrom~；窗口清空时 replayFrom=-1 保留旧水位线
            if (replayFrom >= 0) {
                state.setReplayFromSeq(replayFrom);
            }
            disposed = true;
        } else {
            disposed = dispose(window, level, protectedFrom);
        }

        if (!disposed) {
            return CompressionLevel.NONE;
        }

        return level;
    }

    // ═══════════════════ 档位判定 ═══════════════════

    /** 水位三档自上而下命中即返回；均未命中时轮数入口兜底。 */
    private CompressionLevel resolveLevel(ContextMetrics metrics, int turnCount) {
        if (metrics == null) return CompressionLevel.NONE;

        double water = metrics.waterLevel();
        if (water >= settings.summarizeWaterLevel()) return CompressionLevel.SUMMARIZE;
        if (water >= settings.pruneWaterLevel()) return CompressionLevel.PRUNE;
        if (water >= settings.snipWaterLevel()) return CompressionLevel.SNIP;

        if (turnCount > 0 && turnCount % settings.turnInterval() == 0) {
            return settings.turnLevel();
        }
        return CompressionLevel.NONE;
    }

    // ═══════════════════ 块处置（SNIP / PRUNE） ═══════════════════

    /**
     * 按档位遍历保护区之前的块，把块内容替换为更短的占位文本。
     * 高档位会重新处置低档位已处理过的块，反之不会。
     */
    private boolean dispose(ContextWindow window, CompressionLevel level, int protectedFrom) {
        boolean disposed = false;
        List<ContextBlock> blocks = window.blocks();
        int limit = Math.min(protectedFrom, blocks.size());

        for (int i = 0; i < limit; i++) {
            ContextBlock block = blocks.get(i);
            if (block.disposedAtOrAbove(level)) continue;

            String replacement = replacementFor(block, level);
            if (replacement == null || replacement.length() >= block.chars()) continue;

            block.setText(replacement);
            block.markDisposed(level);
            disposed = true;
        }

        return disposed;
    }

    private String replacementFor(ContextBlock block, CompressionLevel level) {
        return switch (block.kind()) {
            case TOOL_RESULT -> resultReplacement(block, level);
            case TOOL_ARGS -> argsPrune(block, level);
            // AI 正文只参与 SNIP 的头尾截断：推理链被清空比被截断更伤，
            // 截断至少保住开头的问题分析与结尾的结论。
            case AI_TEXT -> level == CompressionLevel.SNIP
                    ? headTailPlaceholder(block, settings.snipKeepChars())
                    : null;
            // 用户消息只有"原文保留"与"被摘要吸收后删除"两个状态
            case USER_INPUT -> null;
        };
    }

    /**
     * 工具结果的替换文本。已落盘且不低于 PRUNE 时清空，否则头尾截断。
     */
    private String resultReplacement(ContextBlock block, CompressionLevel level) {
        if (level.atLeast(CompressionLevel.PRUNE) && block.spilled()) {
            return clearedPlaceholder();
        }
        return headTailPlaceholder(block, settings.snipKeepChars());
    }

    /**
     * 工具参数裁剪：把 JSON 中过长的字符串字段替换为一行占位说明，其余保留。
     * 产出必须是合法 JSON。只在 PRUNE 档触发，短参数不动。
     */
    private String argsPrune(ContextBlock block, CompressionLevel level) {
        if (!level.atLeast(CompressionLevel.PRUNE)) return null;
        if (block.chars() <= settings.argsPruneMinChars()) return null;
        return compressor.skeleton(block.text());
    }

    // ═══════════════════ 占位文本生成 ═══════════════════

    /** 头尾保留截断。返回 null 表示不适合替换。 */
    private String headTailPlaceholder(ContextBlock block, int keepChars) {
        String raw = block.text();
        if (raw.length() <= keepChars) return null;
        return compressor.headTail(raw, keepChars);
    }

    /** 清空为占位文本。 */
    private static String clearedPlaceholder() {
        return "[旧工具结果内容已清除]";
    }
}
