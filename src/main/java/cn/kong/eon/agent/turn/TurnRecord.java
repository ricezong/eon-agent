package cn.kong.eon.agent.turn;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮 Turn 的结构化日志记录，由 {@link TurnLogger#flush} 统一输出摘要日志。
 */
public class TurnRecord {

    int turnNumber;
    long usedTokens;
    long maxTokens;

    int messageCount;
    long estimatedTokens;
    int catalogToolCount;
    /** 上下文构成分解，如 {@code TOOL_ARGS 72% | TOOL_RESULT 26% | AI_TEXT 2%} */
    String composition;

    List<String> toolNames = new ArrayList<>();
    boolean outputTruncated;

    final List<ToolEntry> tools = new ArrayList<>();

    int turnDeltaTokens;
    double waterRatio;

    /** 工具执行明细。 */
    record ToolEntry(String name, boolean success, String argsSummary, int renderedLen) {
    }

    TurnRecord turnHeader(int turnNumber, long usedTokens, long maxTokens) {
        this.turnNumber = turnNumber;
        this.usedTokens = usedTokens;
        this.maxTokens = maxTokens;
        return this;
    }

    TurnRecord context(int messageCount, long estimatedTokens, int catalogToolCount) {
        this.messageCount = messageCount;
        this.estimatedTokens = estimatedTokens;
        this.catalogToolCount = catalogToolCount;
        return this;
    }

    TurnRecord setComposition(String composition) {
        this.composition = composition;
        return this;
    }

    TurnRecord llm(List<String> toolNames) {
        this.toolNames = toolNames != null ? toolNames : List.of();
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

    TurnRecord turnDone(int turnStartTokens, long totalTokens, long maxBudget) {
        this.turnDeltaTokens = (int) (totalTokens - turnStartTokens);
        this.usedTokens = totalTokens;
        this.maxTokens = maxBudget;
        this.waterRatio = maxBudget > 0 ? (double) totalTokens / maxBudget : 0.0;
        return this;
    }
}
