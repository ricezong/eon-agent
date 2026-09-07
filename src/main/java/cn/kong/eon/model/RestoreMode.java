package cn.kong.eon.model;

/**
 * 会话恢复模式：决定账本回放的起点。按快照自洽性自动选择 RESUME 或 LOAD。
 */
public enum RestoreMode {

    /**
     * 快照续接：快照自洽时，恢复摘要/水位线/todo/累计 token，
     * 从 keepFromMessage 起回放假本，不重建完整历史。
     */
    RESUME,

    /** 全量回放：快照缺失/损坏/不自洽时，从账本第 0 行起回放全部历史。 */
    LOAD;

    /**
     * 按快照自洽性选择恢复模式。
     * 不自洽时降级为 LOAD：全量回放最坏是多占 token，按坏快照回放会静默丢历史。
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
