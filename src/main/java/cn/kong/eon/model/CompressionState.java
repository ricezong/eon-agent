package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 压缩状态。记录最新摘要与压缩节奏，供下一轮决策使用。
 * 块级状态（已截断/已裁剪）住在块自身上，不在这里维护。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompressionState {
    private String lastSummary;
    private int summarizedMessageCount;  // 累计已摘要删除的消息数
    private double lastWaterLevel;
    private int lastTurnCompressed;   // 上次轮数触发压缩时的 turnCount，0=从未触发

    public CompressionState() {
        this.summarizedMessageCount = 0;
        this.lastWaterLevel = 0.0;
        this.lastTurnCompressed = 0;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }

    public int getSummarizedMessageCount() {
        return summarizedMessageCount;
    }

    public void setSummarizedMessageCount(int summarizedMessageCount) {
        this.summarizedMessageCount = summarizedMessageCount;
    }

    public double getLastWaterLevel() {
        return lastWaterLevel;
    }

    public void setLastWaterLevel(double lastWaterLevel) {
        this.lastWaterLevel = lastWaterLevel;
    }

    public int getLastTurnCompressed() {
        return lastTurnCompressed;
    }

    public void setLastTurnCompressed(int lastTurnCompressed) {
        this.lastTurnCompressed = lastTurnCompressed;
    }
}
