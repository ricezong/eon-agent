package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮 Turn 的结构化日志记录，由 TurnLogger.flush 统一输出。
 */
public class TurnRecord {

    int turnNumber;
    long usedTokens;
    long maxTokens;
    StopCategory stopCategory;
    int stopGraceRemaining;

    int messageCount;
    long estimatedTokens;
    int catalogToolCount;

    List<String> toolNames = new ArrayList<>();
    int llmDeltaTokens;
    boolean outputTruncated;

    final List<ToolEntry> tools = new ArrayList<>();

    int turnDeltaTokens;
    int okCount;
    int failCount;
    double waterRatio;

    final List<StopEvent> stopEvents = new ArrayList<>();

    record ToolEntry(String name, boolean success, String argsSummary, int renderedLen) {
    }

    enum StopEventType {REQUESTED, ESCALATED, GRACE_CONSUMED}

    record StopEvent(StopEventType type, StopCategory category, String message, int graceRemaining) {
    }


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

    TurnRecord context(int messageCount, long estimatedTokens, int catalogToolCount) {
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.catalogToolCount = catalogToolCount;
        return this;
    }

    TurnRecord llm(List<String> toolNames, int deltaTokens) {
        this.toolNames = toolNames != null ? toolNames : List.of();
        this.llmDeltaTokens = deltaTokens;
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

    TurnRecord turnDone(int turnStartTokens, long totalTokens, long maxBudget,
                        int okCount, int failCount) {
        this.turnDeltaTokens = (int) (totalTokens - turnStartTokens);
        this.usedTokens = totalTokens;
        this.maxTokens = maxBudget;
        this.waterRatio = maxBudget > 0 ? (double) totalTokens / maxBudget : 0.0;
        this.okCount = okCount;
        this.failCount = failCount;
        return this;
    }

    TurnRecord addStopEvent(StopEventType type, StopCategory category, String message, int graceRemaining) {
        this.stopEvents.add(new StopEvent(type, category, message, graceRemaining));
        return this;
    }
}
