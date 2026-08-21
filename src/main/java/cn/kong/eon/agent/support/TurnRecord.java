package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.profile.RequestProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮 Turn 的结构化日志记录。
 *
 * 设计理念：executeTurn 每一步往 TurnRecord 里塞字段，
 * turn 结束后由 {@link TurnLogger#flush(TurnRecord)} 统一格式化输出。
 *
 * 这样做的好处：
 *   1. 一轮 turn 只输出一条大日志，方便阅读和检索
 *   2. 结构化字段可以在 flush 时灵活排版，不散落在各处 log.info()
 *   3. 各步骤只需要 set 对应字段，不关心格式化逻辑
 */
public class TurnRecord {

    // ===== Turn 级别元信息 =====
    int turnNumber;
    RequestProfile profile;
    long usedTokens;
    long maxTokens;
    double tokenRatio;
    StopCategory stopCategory;
    int stopGraceRemaining;

    // ===== Context 阶段 =====
    int messageCount;
    long estimatedTokens;
    boolean hasSummary;
    int catalogToolCount;

    // ===== Mount 阶段 =====
    int mountPhase;
    String mountDetail;

    // ===== LLM 阶段 =====
    String thoughtSummary;
    List<String> toolNames = new ArrayList<>();
    int llmDeltaTokens;
    long llmTotalTokens;
    boolean outputTruncated;

    // ===== Tool 执行阶段 =====
    final List<ToolEntry> tools = new ArrayList<>();
    int toolResultCount;   // flush 回填的工具结果数

    // ===== Turn 汇总 =====
    int turnStartTokens;
    int turnDeltaTokens;
    int okCount;
    int failCount;
    double waterRatio;
    boolean finished;

    // ===== Stop 事件（turn 内发生的 stop 事件，非 agent 级别）=====
    final List<StopEvent> stopEvents = new ArrayList<>();

    // ===== 内部结构 =====

    record ToolEntry(String name, boolean success, String argsSummary, int renderedLen) {}

    enum StopEventType { REQUESTED, ESCALATED, GRACE_CONSUMED }

    record StopEvent(StopEventType type, StopCategory category, String message, int graceRemaining) {}

    // ===== Setter 方法（流式风格，便于链式调用）=====

    TurnRecord turnHeader(int turnNumber, RequestProfile profile, long usedTokens, long maxTokens) {
        this.turnNumber = turnNumber;
        this.profile = profile;
        this.usedTokens = usedTokens;
        this.maxTokens = maxTokens;
        this.tokenRatio = maxTokens > 0 ? (double) usedTokens / maxTokens : 0.0;
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

    TurnRecord mount(int phase, String detail) {
        this.mountPhase = phase;
        this.mountDetail = detail;
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
        this.turnStartTokens = turnStartTokens;
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
