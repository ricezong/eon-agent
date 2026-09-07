package cn.kong.eon.model;

/**
 * 压缩状态：最新摘要与回放水位线。
 * 两者必须成对更新（由 CompressionPolicy 的 SUMMARIZE 分支保证）：
 * 摘要覆盖 #0~keepFrom-1，账本保留 #keepFrom~，拼起来内容完整。
 */
public class CompressionState {
    private String lastSummary;
    /** 回放水位线：窗口删除后首个幸存块的消息序号。 */
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
