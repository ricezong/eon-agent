package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 压缩状态。记录最新摘要与压缩节奏。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompressionState {
    private String lastSummary;
    private int summarizedMessageCount;
    private int lastTurnCompressed;

    public CompressionState() {
        this.summarizedMessageCount = 0;
        this.lastTurnCompressed = 0;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }

    /** 累计已摘要删除的块数。 */
    public int getSummarizedMessageCount() {
        return summarizedMessageCount;
    }

    public void setSummarizedMessageCount(int summarizedMessageCount) {
        this.summarizedMessageCount = summarizedMessageCount;
    }

    /** 上次产生实际压缩效果的轮次序号，供观测与日志使用，不参与档位判定。 */
    public int getLastTurnCompressed() {
        return lastTurnCompressed;
    }

    public void setLastTurnCompressed(int lastTurnCompressed) {
        this.lastTurnCompressed = lastTurnCompressed;
    }
}
