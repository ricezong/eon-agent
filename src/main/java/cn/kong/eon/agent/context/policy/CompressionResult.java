package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.block.CompressionLevel;

/**
 * 一轮压缩的执行结果。
 *
 * @param level          本轮命中的档位
 * @param replacedBlocks 被替换为占位文本的块数
 * @param removedBlocks  被摘要后删除的块数
 * @param charsBefore    处置前窗口字符数
 * @param charsAfter     处置后窗口字符数
 */
public record CompressionResult(
        CompressionLevel level,
        int replacedBlocks,
        int removedBlocks,
        long charsBefore,
        long charsAfter
) {

    /** 未执行任何处置时的结果。 */
    public static CompressionResult none(CompressionLevel level) {
        return new CompressionResult(level, 0, 0, 0, 0);
    }

    /** 本轮是否产生了实际效果。 */
    public boolean applied() {
        return replacedBlocks > 0 || removedBlocks > 0;
    }

    /** 节省的字符数。 */
    public long charsSaved() {
        return Math.max(0, charsBefore - charsAfter);
    }

    /** 本轮降幅。 */
    public double reduction() {
        if (charsBefore <= 0) return 0.0;
        return (double) charsSaved() / charsBefore;
    }

    /** 一行摘要，供日志输出。 */
    public String describe() {
        if (!applied()) return level + "(未生效)";
        StringBuilder sb = new StringBuilder(32);
        sb.append(level);
        if (replacedBlocks > 0) sb.append(" 替换").append(replacedBlocks).append("块");
        if (removedBlocks > 0) sb.append(" 删除").append(removedBlocks).append("块");
        return sb.toString();
    }
}
