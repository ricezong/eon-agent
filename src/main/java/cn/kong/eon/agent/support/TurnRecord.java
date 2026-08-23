package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;

import java.util.ArrayList;
import java.util.List;

/** 单轮 Turn 的结构化日志记录，由 TurnLogger.flush 统一输出。 */
public class TurnRecord {

    int turnNumber;          // 轮次号
    long usedTokens;         // 已用 token
    long maxTokens;          // 预算上限
    StopCategory stopCategory;    // 停止类别
    int stopGraceRemaining;       // 剩余 grace 步数

    // Context
    int messageCount;       // 消息数
    long estimatedTokens;   // 估算 token
    boolean hasSummary;     // 是否有摘要
    int catalogToolCount;   // 工具目录数量

    // LLM
    String thoughtSummary;  // 思考摘要
    List<String> toolNames = new ArrayList<>();  // 工具调用名称
    int llmDeltaTokens;     // 本轮新增 token
    long llmTotalTokens;    // 累计 token
    boolean outputTruncated; // 输出是否被截断

    // Tools
    final List<ToolEntry> tools = new ArrayList<>();  // 工具执行记录
    int toolResultCount;    // 工具结果数

    // Turn 汇总
    int turnDeltaTokens;    // 本轮 delta token
    int okCount;            // 成功数
    int failCount;          // 失败数
    double waterRatio;      // 预算水位

    // Stop 事件
    final List<StopEvent> stopEvents = new ArrayList<>();  // 停止事件列表

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
                        int okCount, int failCount) {
        this.turnDeltaTokens = (int) (totalTokens - turnStartTokens);
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
