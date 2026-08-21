package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Turn 日志器。收集式设计：各步骤写入 TurnRecord，turn 结束后 flush 输出 2 行摘要日志。
 * Agent 级别日志（启动/完成/硬终止）立即打印。
 */
public class TurnLogger {
    private static final Logger log = LoggerFactory.getLogger(TurnLogger.class);

    private final AgentConfig config;

    public TurnLogger(AgentConfig config) {
        this.config = config;
    }

    // ===== Turn 收集方法 =====

    public TurnRecord newRecord() {
        return new TurnRecord();
    }

    public void turnHeader(TurnRecord rec, SessionState state) {
        long used = state.getUsageAccum().getTotalTokens();
        long max = config.getBudget().getMaxTokens();
        rec.turnHeader(state.getTurnCount(), used, max);
        if (state.isStopRequested()) {
            rec.stopInfo(state.getStopState().getReason().getCategory(),
                    state.getStopState().getRemainingGraceSteps());
        }
    }

    public void contextInfo(TurnRecord rec, ContextBuilder ctx, List<ChatMessage> messages,
                            SessionState state, int toolCount) {
        rec.context(messages.size(), ctx.estimateTokens(),
                state.getCompressionState().getLastSummary() != null, toolCount);
    }

    public void llmResponse(TurnRecord rec, String thought, List<ToolExecutionRequest> requests,
                            int deltaTokens, SessionState state) {
        String thoughtSummary = truncate(thought, 100);
        List<String> toolNames = (requests != null && !requests.isEmpty())
                ? requests.stream().map(ToolExecutionRequest::name).toList()
                : List.of();
        rec.llm(thoughtSummary, toolNames, deltaTokens, state.getUsageAccum().getTotalTokens());
    }

    public void outputTruncated(TurnRecord rec) {
        rec.outputTruncated();
    }

    public void toolExecuted(TurnRecord rec, String toolName, boolean success, String argsSummary, int renderedLen) {
        rec.addTool(toolName, success, argsSummary, renderedLen);
    }

    public void flushed(TurnRecord rec, int toolResultCount) {
        rec.flushed(toolResultCount);
    }

    public void stopRequested(TurnRecord rec, StopCategory category, String msg, int grace) {
        rec.addStopEvent(TurnRecord.StopEventType.REQUESTED, category, msg, grace);
    }

    public void stopEscalated(TurnRecord rec, StopCategory category, String msg) {
        rec.addStopEvent(TurnRecord.StopEventType.ESCALATED, category, msg, -1);
    }

    public void graceConsumed(TurnRecord rec, String reason, int remaining) {
        rec.addStopEvent(TurnRecord.StopEventType.GRACE_CONSUMED, null, reason, remaining);
    }

    public void turnDone(TurnRecord rec, SessionState state, int turnStartTokens) {
        List<ToolExecutionResult> results = state.getLastToolResults();
        int toolCount = results != null ? results.size() : 0;
        int okCount = results != null
                ? (int) results.stream().filter(ToolExecutionResult::success).count()
                : 0;
        rec.turnDone(turnStartTokens, state.getUsageAccum().getTotalTokens(),
                config.getBudget().getMaxTokens(), okCount, toolCount - okCount, state.isFinished());
    }

    // ===== 统一 flush：2 行摘要 =====

    public void flush(TurnRecord rec) {
        // Line 1: Header — Turn 号 + 上下文 + LLM 摘要
        StringBuilder header = new StringBuilder(256);
        header.append("Turn ").append(rec.turnNumber);
        if (rec.stopCategory != null) {
            header.append(" ⚠").append(rec.stopCategory)
                  .append("(grace=").append(rec.stopGraceRemaining).append(")");
        }
        header.append(" │ ctx ").append(rec.messageCount).append("msgs/~").append(rec.estimatedTokens).append("tok");
        header.append(" │ LLM: ").append(rec.toolNames.isEmpty() ? "—" : rec.toolNames.toString());
        if (rec.llmDeltaTokens > 0) {
            header.append(" +").append(rec.llmDeltaTokens).append("tok");
        }
        if (rec.outputTruncated) {
            header.append(" ⚠truncated");
        }
        for (TurnRecord.ToolEntry tool : rec.tools) {
            header.append(" │ ").append(tool.name()).append(tool.success() ? "✓" : "✗");
        }
        log.info(header.toString());

        // Line 2: Done — 汇总
        StringBuilder done = new StringBuilder(128);
        done.append("  └ Turn ").append(rec.turnNumber).append(" done");
        done.append(" │ ").append(rec.okCount).append("ok/").append(rec.failCount).append("fail");
        done.append(" │ Δ+").append(rec.turnDeltaTokens).append("tok");
        done.append(" │ total ").append(rec.usedTokens).append("/").append(rec.maxTokens);
        done.append(" (").append(String.format("%.0f", rec.waterRatio * 100)).append("%)");
        if (rec.finished) done.append(" │ ✓finish");
        log.info(done.toString());

        // Stop 事件（如有）
        for (TurnRecord.StopEvent event : rec.stopEvents) {
            String detail = switch (event.type()) {
                case REQUESTED -> "STOP requested: " + event.category() + " │ " + event.message() + " │ grace=" + event.graceRemaining();
                case ESCALATED -> "STOP escalated: " + event.category() + " │ " + event.message();
                case GRACE_CONSUMED -> "GRACE consumed (" + event.message() + ") │ remaining=" + event.graceRemaining();
            };
            log.warn("  │ {}", detail);
        }
    }

    // ===== Agent 级别日志（立即打印）=====

    public void agentStart(SessionState state) {
        log.info("┌─ EonAgent 启动 │ session: {} │ maxSteps: {} │ budget: {} tokens",
                state.getSessionId(), config.getLoop().maxSteps, config.getBudget().getMaxTokens());
        log.info("├─ 用户请求: {}", state.getUserOriginalInput());
    }

    public void agentFinish(SessionState state) {
        log.info("└─ EonAgent 完成 │ turns={} │ tokens={} │ ✓ finish",
                state.getTurnCount(), state.getUsageAccum().getTotalTokens());
    }

    public void stopForced(String category, int turns, int tokens) {
        log.warn("└─ ⚠ 强制终止: {} │ turns={} │ tokens={}", category, turns, tokens);
    }

    // ===== 工具方法 =====

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
