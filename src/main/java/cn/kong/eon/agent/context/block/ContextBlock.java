package cn.kong.eon.agent.context.block;

import java.util.Objects;

/**
 * 上下文内容块。上下文领域模型的最小单位。
 * 与 LangChain4j 的 ChatMessage 的区别：ChatMessage 是传输类型（一条消息可含多块内容），
 * ContextBlock 是领域类型（一块内容 = 一个可独立处置的单元）。两者通过 BlockProjector 双向投射。
 * 块上携带三个决定其如何被处置的属性：{@link Retention}（能否改写）、
 * {@code recoverable}（磁盘上有无副本）、{@link CompressionLevel}（已施加的处置档位）。
 */
public final class ContextBlock {

    private final String id;
    private final BlockKind kind;
    private final Retention retention;
    /** 来源消息组 id。同一条 ChatMessage 拆出的块共享 groupId，用于重组回消息 */
    private final String groupId;
    /** 组内序号，重组时恢复原始顺序 */
    private final int ordinal;
    /** 入站轮次。尾部保护区按轮次判定 */
    private final int turn;
    /** 工具名（仅 TOOL_ARGS / TOOL_RESULT） */
    private final String toolName;
    /** 工具调用 id（仅 TOOL_ARGS / TOOL_RESULT），用于配对 */
    private final String toolCallId;
    /** 入站时的原文长度，用于度量"已节省多少 token" */
    private final int originalChars;

    private String text;
    /** 落盘 artifact 引用 id。非空表示磁盘上有完整副本 */
    private String refId;
    /** 磁盘上是否存在完整副本。为 true 时清空内容不损失信息。 */
    private boolean recoverable;
    /** 已施加的最高处置档位。档位单调递增，高档位可覆盖低档位的结果。 */
    private CompressionLevel disposedLevel;

    private ContextBlock(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.retention = b.retention != null ? b.retention : Retention.COMPRESSIBLE;
        this.groupId = Objects.requireNonNull(b.groupId, "groupId");
        this.ordinal = b.ordinal;
        this.turn = b.turn;
        this.toolName = b.toolName;
        this.toolCallId = b.toolCallId;
        this.text = b.text != null ? b.text : "";
        this.originalChars = this.text.length();
        this.disposedLevel = CompressionLevel.NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ═══════════════════ 标识 ═══════════════════

    public String id() {
        return id;
    }

    public BlockKind kind() {
        return kind;
    }

    public Retention retention() {
        return retention;
    }

    public String groupId() {
        return groupId;
    }

    public int ordinal() {
        return ordinal;
    }

    public int turn() {
        return turn;
    }

    public String toolName() {
        return toolName;
    }

    public String toolCallId() {
        return toolCallId;
    }

    // ═══════════════════ 内容 ═══════════════════

    public String text() {
        return text;
    }

    /** 原地改写内容。压缩处置通过它作用到块上。 */
    public void setText(String newText) {
        this.text = newText != null ? newText : "";
    }

    public int chars() {
        return text.length();
    }

    /** 落盘 artifact 引用 id。非空即表示磁盘上存在完整副本。 */
    public String refId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    /** 入站时的原始字符数 */
    public int originalChars() {
        return originalChars;
    }

    /** 相对入站已节省的字符数（含无损替换与有损压缩） */
    public int savedChars() {
        return Math.max(0, originalChars - text.length());
    }

    // ═══════════════════ 处置属性 ═══════════════════

    /** 磁盘上是否存在完整副本。为 true 时清空内容不损失信息。 */
    public boolean recoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }

    /** 已施加的最高处置档位，未处置过为 {@link CompressionLevel#NONE}。 */
    public CompressionLevel disposedLevel() {
        return disposedLevel;
    }

    /** 是否已接受过不低于给定档位的处置。已接受则无需重复处置。 */
    public boolean disposedAtOrAbove(CompressionLevel level) {
        return disposedLevel.atLeast(level);
    }

    /** 记录已施加的档位，取历史与本次中的较高者，保证单调不回落。 */
    public void markDisposed(CompressionLevel level) {
        this.disposedLevel = disposedLevel.higherOf(level);
    }

    // ═══════════════════ 构造 ═══════════════════

    public static final class Builder {
        private String id;
        private BlockKind kind;
        private Retention retention = Retention.COMPRESSIBLE;
        private String groupId;
        private int ordinal;
        private int turn;
        private String toolName;
        private String toolCallId;
        private String text;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder kind(BlockKind v) {
            this.kind = v;
            return this;
        }

        public Builder retention(Retention v) {
            this.retention = v;
            return this;
        }

        public Builder groupId(String v) {
            this.groupId = v;
            return this;
        }

        public Builder ordinal(int v) {
            this.ordinal = v;
            return this;
        }

        public Builder turn(int v) {
            this.turn = v;
            return this;
        }

        public Builder toolName(String v) {
            this.toolName = v;
            return this;
        }

        public Builder toolCallId(String v) {
            this.toolCallId = v;
            return this;
        }

        public Builder text(String v) {
            this.text = v;
            return this;
        }

        public ContextBlock build() {
            return new ContextBlock(this);
        }
    }

    @Override
    public String toString() {
        return "Block{" + kind + "/" + retention
                + " id=" + id
                + " turn=" + turn
                + " chars=" + text.length()
                + (originalChars != text.length() ? " (原 " + originalChars + ")" : "")
                + (disposedLevel != CompressionLevel.NONE ? " 档位=" + disposedLevel : "")
                + '}';
    }
}
