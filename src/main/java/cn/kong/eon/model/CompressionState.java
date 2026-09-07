package cn.kong.eon.model;

/**
 * 压缩状态。存放最新摘要与回放水位线。
 * <p>
 * 两者必须成对更新（由 CompressionPolicy 的 SUMMARIZE 分支保证）：
 * 摘要覆盖账本 #0 ~ keepFromMessage-1，账本保留 #keepFromMessage ~，
 * 会话恢复时拼起来内容完整——拆开存会出现摘要与回放起点对不上的窗口。
 * <p>
 * 压缩节奏与效果统计走日志（CtxCompactHook 每轮输出），不在此堆积观测字段。
 */
public class CompressionState {
    private String lastSummary;
    /** 回放水位线：窗口删除后第一个幸存块的消息序号，恢复时从此处回放账本 */
    private int keepFromMessage;

    public CompressionState() {
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }

    public int getKeepFromMessage() {
        return keepFromMessage;
    }

    public void setKeepFromMessage(int keepFromMessage) {
        this.keepFromMessage = keepFromMessage;
    }
}
