package cn.kong.eon.model;

/**
 * 会话恢复模式：决定账本回放的起点。
 * <p>
 * 对应 ACP 协议里 {@code session/resume}（不回放历史，直接续）与
 * {@code session/load}（回放历史）的区分，区别在于"回不回放"，
 * 而不在于是否有常驻进程——eon-agent 进程退出即结束，因此改为按快照自洽性自动选择。
 */
public enum RestoreMode {

    /**
     * 快照续接：快照存在且自洽，恢复摘要/水位线/todo/累计 token，
     * 只从 {@code keepFromMessage} 起回放假本，不重建完整历史。
     */
    RESUME,

    /** 全量回放：快照缺失、损坏或不自洽，从账本第 0 行起回放全部历史。 */
    LOAD;

    /**
     * 按快照自洽性选择恢复模式。
     * 自洽 = 回放水位线落在账本范围内，且摘要与水位线成对——摘要覆盖 {@code #0~keepFrom-1}，
     * 账本保留 {@code #keepFrom~}，两者拆开就会错位。
     * <p>
     * 不自洽时降级为 LOAD：全量回放最坏是多占 token，而按坏快照回放会静默丢掉历史。
     *
     * @param cp         会话快照，null 表示无快照
     * @param ledgerSize 账本行数
     */
    public static RestoreMode of(SessionSnapshot cp, long ledgerSize) {
        if (cp == null || cp.getCompressionState() == null) return LOAD;
        CompressionState cs = cp.getCompressionState();
        int keepFrom = cs.getKeepFromMessage();
        boolean hasSummary = cs.getLastSummary() != null && !cs.getLastSummary().isBlank();
        boolean inRange = keepFrom >= 0 && keepFrom <= ledgerSize;
        return inRange && (keepFrom > 0) == hasSummary ? RESUME : LOAD;
    }
}
