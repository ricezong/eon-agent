package cn.kong.eon.agent.context.block;

/**
 * 压缩档位。每轮至多命中一个档位。
 */
public enum CompressionLevel {

    /** 不处置。 */
    NONE(0),

    /** 头尾保留截断。 */
    SNIP(1),

    /** 清空内容，替换为占位文本。仅对磁盘上有副本的块执行。 */
    PRUNE(2),

    /** 由 LLM 生成摘要后删除原文。 */
    SUMMARIZE(3);

    private final int severity;

    CompressionLevel(int severity) {
        this.severity = severity;
    }

    /** 是否执行处置。NONE 表示本轮不压缩。 */
    public boolean enabled() {
        return this != NONE;
    }

    /** 本档位是否不低于给定档位。 */
    public boolean atLeast(CompressionLevel other) {
        return other != null && severity >= other.severity;
    }

    /** 两个档位中力度较高的一个。 */
    public CompressionLevel higherOf(CompressionLevel other) {
        return other == null || severity >= other.severity ? this : other;
    }
}
