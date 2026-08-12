package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 压缩状态：记录哪些消息已被 Snip / Prune / Summarize。
 * 对应技术方案第 2.5 节 CompressionState。
 * 压缩决策单调推进——同一消息一旦被 Snip，不会回退为完整。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompressionState {
    private Set<String> snippedIds;       // 已 Snip 的消息 ID
    private Set<String> prunedIds;        // 已 Prune 的消息 ID
    private String lastSummary;           // 最近一次 LLM 摘要文本
    private int summarizedUpToIndex;      // 摘要覆盖到哪条消息
    private double lastWaterLevel;        // 上次水位

    public CompressionState() {
        this.snippedIds = new HashSet<>();
        this.prunedIds = new HashSet<>();
        this.summarizedUpToIndex = -1;
        this.lastWaterLevel = 0.0;
    }

    public boolean isSnipped(String id) { return snippedIds.contains(id); }
    public boolean isPruned(String id) { return prunedIds.contains(id); }

    public void markSnipped(String id) { snippedIds.add(id); }
    public void markPruned(String id) {
        prunedIds.add(id);
        snippedIds.add(id);  // Prune 隐含 Snip
    }

    public Set<String> getSnippedIds() { return snippedIds; }
    public void setSnippedIds(Set<String> snippedIds) { this.snippedIds = snippedIds; }

    public Set<String> getPrunedIds() { return prunedIds; }
    public void setPrunedIds(Set<String> prunedIds) { this.prunedIds = prunedIds; }

    public String getLastSummary() { return lastSummary; }
    public void setLastSummary(String lastSummary) { this.lastSummary = lastSummary; }

    public int getSummarizedUpToIndex() { return summarizedUpToIndex; }
    public void setSummarizedUpToIndex(int summarizedUpToIndex) { this.summarizedUpToIndex = summarizedUpToIndex; }

    public double getLastWaterLevel() { return lastWaterLevel; }
    public void setLastWaterLevel(double lastWaterLevel) { this.lastWaterLevel = lastWaterLevel; }
}
