package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Turn 日志器。收集式设计：各步骤写入 TurnRecord，turn 结束后 flush 输出 2 行摘要日志。
 */
public class TurnLogger {
    private static final Logger log = LoggerFactory.getLogger(TurnLogger.class);

    private final AgentConfig config;

    public TurnLogger(AgentConfig config) {
        this.config = config;
    }


    public TurnRecord newRecord() {
        return new TurnRecord();
    }

    public void turnHeader(TurnRecord rec, SessionState state) {
        long used = state.getUsageAccum().getTotalTokens();
        long max = config.getBudget().getMaxTokens();
        rec.turnHeader(state.getTurnCount(), used, max);
        if (state.isStopRequested()) {
            rec.stopInfo(state.getStopState().getReason().getCategory(), state.getStopState().getRemainingGraceSteps());
        }
    }

    public void contextInfo(TurnRecord rec, ContextBuilder ctx, List<ChatMessage> messages, SessionState state, int toolCount) {
        rec.context(messages.size(), ctx.estimateTokens(), toolCount);
    }

    public void llmResponse(TurnRecord rec, List<ToolExecutionRequest> requests) {
        List<String> toolNames = (requests != null && !requests.isEmpty()) ? requests.stream().map(ToolExecutionRequest::name).toList() : List.of();
        rec.llm(toolNames);
    }

    public void outputTruncated(TurnRecord rec) {
        rec.outputTruncated();
    }

    public void toolExecuted(TurnRecord rec, String toolName, boolean success, String argsSummary, int renderedLen) {
        rec.addTool(toolName, success, argsSummary, renderedLen);
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
        rec.turnDone(turnStartTokens, state.getUsageAccum().getTotalTokens(), config.getBudget().getMaxTokens());
    }


    public void flush(TurnRecord rec) {
        StringBuilder line = new StringBuilder(192);
        line.append("Turn ").append(rec.turnNumber).append(" 完成");
        if (rec.stopCategory != null) {
            line.append(" ⚠").append(rec.stopCategory).append("(宽限剩余").append(rec.stopGraceRemaining).append(")");
        }
        long ctxMaxTokens = config.getContext().getMaxTokens();
        double ctxRatio = ctxMaxTokens > 0 ? (double) rec.estimatedTokens / ctxMaxTokens : 0.0;
        line.append(" │ 上下文 ").append(rec.messageCount).append(" 条消息 (约 ")
                .append(rec.estimatedTokens).append("/").append(ctxMaxTokens)
                .append(" token, 占用 ").append(String.format("%.0f", ctxRatio * 100)).append("%)");

        if (!rec.tools.isEmpty()) {
            line.append(" │ 工具: ");
            for (int i = 0; i < rec.tools.size(); i++) {
                TurnRecord.ToolEntry tool = rec.tools.get(i);
                if (i > 0) {
                    line.append(", ");
                }
                line.append(tool.name()).append(tool.success() ? " ✓" : " ✗");
            }
        } else if (!rec.toolNames.isEmpty()) {
            // LLM 请求了工具但未执行（被 PostModel Hook 跳过等）
            line.append(" │ LLM 请求工具 ").append(rec.toolNames).append("（未执行）");
        } else {
            line.append(" │ LLM 输出最终回复");
        }
        if (rec.outputTruncated) {
            line.append(" │ ⚠输出被截断");
        }

        line.append(" │ 本轮 +").append(rec.turnDeltaTokens)
                .append(" token │ 预算累计 ").append(rec.usedTokens).append("/")
                .append(rec.maxTokens)
                .append(" (").append(String.format("%.0f", rec.waterRatio * 100)).append("%)");
        log.info(line.toString());

        if (!rec.tools.isEmpty() && log.isDebugEnabled()) {
            for (TurnRecord.ToolEntry tool : rec.tools) {
                log.debug("  工具明细: {} 参数={} 输出 {} 字符", tool.name(), tool.argsSummary(), tool.renderedLen());
            }
        }

        for (TurnRecord.StopEvent event : rec.stopEvents) {
            String detail = switch (event.type()) {
                case REQUESTED ->
                        "停止请求: " + event.category() + " │ " + event.message() + " │ 宽限期=" + event.graceRemaining();
                case ESCALATED -> "停止升级: " + event.category() + " │ " + event.message();
                case GRACE_CONSUMED -> "宽限期消耗 (" + event.message() + ") │ 剩余=" + event.graceRemaining();
            };
            log.warn("  │ {}", detail);
        }
    }


    public void agentStart(SessionState state) {
        log.info("┌─ EonAgent 启动 │ 会话: {} │ 最大步数: {} │ 预算: {} tokens", state.getSessionId(), config.getLoop().getMaxSteps(), config.getBudget().getMaxTokens());
        log.info("├─ 用户请求: {}", state.getUserInput());
    }

    public void agentComplete(SessionState state) {
        log.info("└─ EonAgent 完成 │ turns={} │ tokens={}", state.getTurnCount(), state.getUsageAccum().getTotalTokens());
    }

    public void stopForced(String category, int turns, int tokens) {
        log.warn("└─ ⚠ 强制终止: {} │ turns={} │ tokens={}", category, turns, tokens);
    }


}
