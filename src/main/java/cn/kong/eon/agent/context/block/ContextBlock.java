package cn.kong.eon.agent.context.block;

import java.util.Objects;

/**
 * 上下文内容块。上下文领域模型的最小单位。
 * <p>
 * 与 LangChain4j 的 {@code ChatMessage} 的区别：
 * {@code ChatMessage} 是<b>传输类型</b>（一条消息可含多块内容），
 * {@code ContextBlock} 是<b>领域类型</b>（一块内容 = 一个可独立处置的单元）。
 * 两者通过 {@link BlockProjector} 双向投射。
 * <p>
 * 状态（是否已卸载 / 已截断 / 已裁剪）住在块自身上，而不是外部的去重集合里。
 * 这样"压缩进度"与"被压缩的内容"永不脱离——过去状态存在
 * {@code CompressionState.snippedIds} 里，而内存视图重启后从磁盘原文重建，
 * 两者会失配；块级状态随内存视图同生共死，不存在这个问题。
 */
public final class ContextBlock {

    private final String id;
    private final BlockKind kind;
    private final Retention retention;
    /** 来源消息组 id。同一条 ChatMessage 拆出的块共享 groupId，用于重组回消息 */
    private final String groupId;
    /** 组内序号，重组时恢复原始顺序 */
    private final int ordinal;
    /** 入站轮次。尾部保护区按轮次判定，而非按消息条数近似 */
    private final int turn;
    /** 工具名（仅 TOOL_ARGS / TOOL_RESULT） */
    private final String toolName;
    /** 工具调用 id（仅 TOOL_ARGS / TOOL_RESULT），用于配对 */
    private final String toolCallId;
    /** 入站时的原文长度，用于度量"已节省多少 token" */
    private final int originalChars;

    private String text;
    /** 落盘 artifact 引用 id（如 art_001）。非空表示磁盘上有完整副本 */
    private String refId;
    /**
     * 工具结果块标记的执行成功与否；null 表示未知（非结果块，或从历史恢复）。
     * <p>
     * 这个字段是<b>无损卸载的安全判据</b>：只有执行成功的调用才保证参数内容真正落盘，
     * 失败的调用卸载会永久丢失内容。放在块上，策略层才能在不接触工具层的情况下做出判断。
     */
    private Boolean success;
    private boolean offloaded;
    private boolean snipped;
    private boolean pruned;

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
        this.offloaded = b.offloaded;
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

    /**
     * 原地改写内容。压缩与卸载规则通过它作用到块上。
     */
    public void setText(String newText) {
        this.text = newText != null ? newText : "";
    }

    public int chars() {
        return text.length();
    }

    /**
     * 落盘 artifact 引用 id。非空即表示磁盘上存在完整副本，
     * 此时把块替换为纯引用是<b>无损</b>操作。
     */
    public String refId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    /** 执行成功与否；null = 未知 */
    public Boolean success() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    /** 入站时的原始字符数 */
    public int originalChars() {
        return originalChars;
    }

    /** 相对入站已节省的字符数（含卸载与有损压缩） */
    public int savedChars() {
        return Math.max(0, originalChars - text.length());
    }

    // ═══════════════════ 状态 ═══════════════════

    public boolean isOffloaded() {
        return offloaded;
    }

    public void markOffloaded() {
        this.offloaded = true;
    }

    public boolean isSnipped() {
        return snipped;
    }

    public void markSnipped() {
        this.snipped = true;
    }

    public boolean isPruned() {
        return pruned;
    }

    /** Prune 隐含 Snip：被裁剪的块无需再截断 */
    public void markPruned() {
        this.pruned = true;
        this.snipped = true;
    }

    /**
     * 是否还有处置空间：已被裁剪的块不再参与任何规则。
     */
    public boolean isDisposed() {
        return pruned;
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
        private boolean offloaded;

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

        public Builder offloaded(boolean v) {
            this.offloaded = v;
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
                + '}';
    }
}
