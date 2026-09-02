package cn.kong.eon.agent.context.block;

/**
 * 压缩档位。上下文处置力度的阶梯，每轮至多命中一个档位。
 * <p>
 * 档位同时承担两个职责：
 * <ul>
 *   <li>本轮对整个窗口施加的处置力度（由 {@code CompressionTrigger} 判定）</li>
 *   <li>单个块已被施加过的处置力度（记录在块上，用于判断该块是否还需再处置）</li>
 * </ul>
 * 有序性由 {@link #severity} 表达，高档位包含低档位的动作。
 */
public enum CompressionLevel {

    /** 不处置。 */
    NONE(0),

    /** 头尾保留截断。保留开头与结尾，省略中段。 */
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

    /** 本档位是否严格高于给定档位。 */
    public boolean above(CompressionLevel other) {
        return other != null && severity > other.severity;
    }

    /** 两个档位中力度较高的一个。用于把块状态单调推向更高档位。 */
    public CompressionLevel higherOf(CompressionLevel other) {
        return other == null || severity >= other.severity ? this : other;
    }
}
