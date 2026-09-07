package cn.kong.eon.agent.support;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Turn 日志器。收集式设计：各步骤写入 TurnRecord，turn 结束后 flush 输出摘要日志。
 */
public class TurnLogger {
    private static final Logger log = LoggerFactory.getLogger(TurnLogger.class);

    private final AgentConfig config;

    public TurnLogger(AgentConfig config) {
        this.config = config;
    }

    /** 创建新的日志记录对象。 */
    public TurnRecord newRecord() {
        return new TurnRecord();
    }

    /** 记录轮次头信息：轮次号、已用/最大 token。 */
    public void turnHeader(TurnRecord rec, SessionState state) {
        long used = state.getUsageAccum().getTotalTokens();
        long max = config.getBudget().getMaxTokens();
        rec.turnHeader(state.getTurnCount(), used, max);
    }

    /** 记录上下文信息：消息数、估算 token、工具数、构成分解。 */
    public void contextInfo(TurnRecord rec, ContextMetrics metrics, int msgCount, int toolCount) {
        rec.context(msgCount, metrics.sentTokens(), toolCount);
        rec.setComposition(metrics.composition());
    }

    /** 记录 LLM 响应中请求的工具列表。 */
    public void llmResponse(TurnRecord rec, List<ToolExecutionRequest> requests) {
        List<String> toolNames = (requests != null && !requests.isEmpty()) ? requests.stream().map(ToolExecutionRequest::name).toList() : List.of();
        rec.llm(toolNames);
    }

    /** 标记输出被截断。 */
    public void outputTruncated(TurnRecord rec) {
        rec.outputTruncated();
    }

    /** 记录单个工具的执行结果。 */
    public void toolExecuted(TurnRecord rec, String toolName, boolean success, String argsSummary, int renderedLen) {
        rec.addTool(toolName, success, argsSummary, renderedLen);
    }

    /** 记录轮次结束时的 token 统计。 */
    public void turnDone(TurnRecord rec, SessionState state) {
        rec.turnDone(state.getUsageAccum().getTotalTokens(), state.getUsageAccum().getTotalTokens(), config.getBudget().getMaxTokens());
    }

    /** 输出轮次摘要日志（INFO 级摘要 + DEBUG 级工具明细）。 */
    public void flush(TurnRecord rec) {
        StringBuilder line = new StringBuilder(192);
        line.append("Turn ").append(rec.turnNumber).append(" 完成");
        long ctxMaxTokens = config.getContext().getMaxTokens();
        double ctxRatio = ctxMaxTokens > 0 ? (double) rec.estimatedTokens / ctxMaxTokens : 0.0;
        line.append(" │ 上下文 ").append(rec.messageCount).append(" 条消息 (约 ")
                .append(rec.estimatedTokens).append("/").append(ctxMaxTokens)
                .append(" token, 占用 ").append(String.format("%.0f", ctxRatio * 100)).append("%)");
        if (rec.composition != null && !rec.composition.isBlank()) {
            line.append(" │ ").append(rec.composition);
        }

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
    }

    /** Agent 启动日志。 */
    public void agentStart(SessionState state) {
        log.info("┌─ EonAgent 启动 │ 会话: {} │ 最大步数: {} │ 预算: {} tokens", state.getSessionId(), config.getLoop().getMaxSteps(), config.getBudget().getMaxTokens());
        log.info("├─ 用户请求: {}", state.getUserInput());
    }

    /** Agent 正常完成日志。 */
    public void agentComplete(SessionState state) {
        log.info("└─ EonAgent 完成 │ turns={} │ tokens={}", state.getTurnCount(), state.getUsageAccum().getTotalTokens());
    }

    /** Agent 被强制终止日志。 */
    public void stopForced(String category, int turns, int tokens) {
        log.warn("└─ ⚠ 强制终止: {} │ turns={} │ tokens={}", category, turns, tokens);
    }
}
