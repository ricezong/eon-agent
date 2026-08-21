package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮 Turn 的结构化日志记录。
 * 收集各步骤信息，turn 结束后由 TurnLogger.flush 统一输出 2 行摘要日志。
 */
public class TurnRecord {

    int turnNumber;
    long usedTokens;
    long maxTokens;
    StopCategory stopCategory;
    int stopGraceRemaining;

    // Context
    int messageCount;
    long estimatedTokens;
    boolean hasSummary;
    int catalogToolCount;

    // LLM
    String thoughtSummary;
    List<String> toolNames = new ArrayList<>();
    int llmDeltaTokens;
    long llmTotalTokens;
    boolean outputTruncated;

    // Tools
    final List<ToolEntry> tools = new ArrayList<>();
    int toolResultCount;

    // Turn 汇总
    int turnDeltaTokens;
    int okCount;
    int failCount;
    double waterRatio;
    boolean finished;

    // Stop 事件
    final List<StopEvent> stopEvents = new ArrayList<>();

    record ToolEntry(String name, boolean success, String argsSummary, int renderedLen) {}

    enum StopEventType { REQUESTED, ESCALATED, GRACE_CONSUMED }

    record StopEvent(StopEventType type, StopCategory category, String message, int graceRemaining) {}

    // ===== Setter 方法 =====

    TurnRecord turnHeader(int turnNumber, long usedTokens, long maxTokens) {
        this.turnNumber = turnNumber;
        this.usedTokens = usedTokens;
        this.maxTokens = maxTokens;
        return this;
    }

    TurnRecord stopInfo(StopCategory category, int graceRemaining) {
        this.stopCategory = category;
        this.stopGraceRemaining = graceRemaining;
        return this;
    }

    TurnRecord context(int messageCount, long estimatedTokens, boolean hasSummary, int catalogToolCount) {
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.hasSummary = hasSummary;
        this.catalogToolCount = catalogToolCount;
        return this;
    }

    TurnRecord llm(String thoughtSummary, List<String> toolNames, int deltaTokens, long totalTokens) {
        this.thoughtSummary = thoughtSummary;
        this.toolNames = toolNames != null ? toolNames : List.of();
        this.llmDeltaTokens = deltaTokens;
        this.llmTotalTokens = totalTokens;
        return this;
    }

    TurnRecord outputTruncated() {
        this.outputTruncated = true;
        return this;
    }

    TurnRecord addTool(String name, boolean success, String argsSummary, int renderedLen) {
        this.tools.add(new ToolEntry(name, success, argsSummary, renderedLen));
        return this;
    }

    TurnRecord flushed(int toolResultCount) {
        this.toolResultCount = toolResultCount;
        return this;
    }

    TurnRecord turnDone(int turnStartTokens, long totalTokens, long maxBudget,
                        int okCount, int failCount, boolean finished) {
        this.turnDeltaTokens = (int) (totalTokens - turnStartTokens);
        this.waterRatio = maxBudget > 0 ? (double) totalTokens / maxBudget : 0.0;
        this.okCount = okCount;
        this.failCount = failCount;
        this.finished = finished;
        return this;
    }

    TurnRecord addStopEvent(StopEventType type, StopCategory category, String message, int graceRemaining) {
        this.stopEvents.add(new StopEvent(type, category, message, graceRemaining));
        return this;
    }
}
