package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashSet;
import java.util.Set;

/**
 * 压缩状态。记录哪些消息已被 Snip / Prune / Summarize。
 * 压缩决策单调推进——同一消息一旦被 Snip，不会回退为完整。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompressionState {
    private Set<String> snippedIds;
    private Set<String> prunedIds;
    private String lastSummary;
    private int summarizedUpToIndex;
    private double lastWaterLevel;

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
