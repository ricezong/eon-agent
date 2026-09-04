package cn.kong.eon.model;

/**
 * 压缩状态。存放最新摘要。
 * <p>
 * 压缩节奏与效果统计走日志（CtxCompactHook 每轮输出），不在此堆积观测字段。
 */
public class CompressionState {
    private String lastSummary;

    public CompressionState() {
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }
}
