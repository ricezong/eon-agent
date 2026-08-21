package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.profile.RequestProfile;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Turn 日志器。收集式设计：executeTurn 每一步将信息写入 {@link TurnRecord}，
 * turn 结束后调用 {@link #flush(TurnRecord)} 统一格式化输出一条完整 turn 日志。
 *
 * 职责分工：
 *   - agent 级别日志（启动/完成/硬终止）→ 立即打印，不属于单轮 turn
 *   - turn 级别日志（header/context/llm/tool/done/stop 事件）→ 收集到 TurnRecord，统一 flush
 *   - summarizeArgs → 纯工具方法，供 ToolExecutionHandler 调用
 */
public class TurnLogger {
    private static final Logger log = LoggerFactory.getLogger(TurnLogger.class);

    private final AgentConfig config;

    public TurnLogger(AgentConfig config) {
        this.config = config;
    }

    // ===== Turn 收集方法（写入 TurnRecord，不立即打印）=====

    public TurnRecord newRecord() {
        return new TurnRecord();
    }

    public void turnHeader(TurnRecord rec, SessionState state, RequestProfile profile) {
        long used = state.getUsageAccum().getTotalTokens();
        long max = config.getBudget().getMaxTokens();
        rec.turnHeader(state.getTurnCount(), profile, used, max);
        if (state.isStopRequested()) {
            rec.stopInfo(state.getStopState().getReason().getCategory(),
                    state.getStopState().getRemainingGraceSteps());
        }
    }

    public void contextInfo(TurnRecord rec, ContextBuilder ctx, List<ChatMessage> messages, SessionState state, int toolCount) {
        rec.context(messages.size(), ctx.estimateTokens(),
                state.getCompressionState().getLastSummary() != null, toolCount);
    }

    public void mountPhase(TurnRecord rec, int phase, String detail) {
        rec.mount(phase, detail);
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
        List<cn.kong.eon.model.ToolExecutionResult> results = state.getLastToolResults();
        int toolCount = results != null ? results.size() : 0;
        int okCount = results != null
                ? (int) results.stream().filter(r -> !r.content().startsWith("[ERROR]")).count()
                : 0;
        rec.turnDone(turnStartTokens, state.getUsageAccum().getTotalTokens(),
                config.getBudget().getMaxTokens(), okCount, toolCount - okCount, state.isFinished());
    }

    // ===== 统一 flush：将 TurnRecord 格式化为一条完整日志 =====

    public void flush(TurnRecord rec) {
        // SLF4J 的 log.info 会自动在 \n 处断行并产生空行，因此用多行 log 调用替代单条多行字符串。
        // 每一行都带统一前缀 "  │ " 形成视觉块，最后一行用 "  └─" 收尾。

        String prefix = "  │ ";
        String footer = "  └─";

        // Line 1: Header — Turn 号 + Profile + Token 概览
        StringBuilder header = new StringBuilder(128);
        header.append("Turn ").append(rec.turnNumber)
              .append(" [").append(rec.profile).append("]");
        if (rec.stopCategory != null) {
            header.append(" ⚠ ").append(rec.stopCategory)
                  .append("(grace=").append(rec.stopGraceRemaining).append(")");
        }
        header.append(" │ tokens ").append(rec.usedTokens).append("/").append(rec.maxTokens)
              .append(" (").append(String.format("%.0f", rec.tokenRatio * 100)).append("%)");
        log.info(prefix + header);

        // Line 2: Context
        log.info(prefix + "Context │ {} msgs │ ~{} tok │ summary={} │ {} tools in catalog",
                rec.messageCount, rec.estimatedTokens, rec.hasSummary ? "✓" : "✗", rec.catalogToolCount);

        // Line 3: Mount (optional)
        if (rec.mountDetail != null) {
            log.info(prefix + "Mount  │ phase {} │ {}", rec.mountPhase, rec.mountDetail);
        }

        // Line 4: LLM
        String thoughtDisplay = (rec.thoughtSummary == null || rec.thoughtSummary.isBlank())
                ? "(none)" : "\"" + rec.thoughtSummary + "\"";
        String toolsDisplay = rec.toolNames.isEmpty() ? "—" : rec.toolNames.toString();
        log.info(prefix + "LLM    │ thought={} │ tools={} │ +{} tok ({} total)",
                thoughtDisplay, toolsDisplay, rec.llmDeltaTokens, rec.llmTotalTokens);

        if (rec.outputTruncated) {
            log.warn(prefix + "⚠ output truncated (finishReason=length), tool call may be lost");
        }

        // Lines: Tools
        for (TurnRecord.ToolEntry tool : rec.tools) {
            log.info(prefix + "Tool   │ {} {} │ {} │ {} chars",
                    tool.name(), tool.success() ? "✓" : "✗", tool.argsSummary(), tool.renderedLen());
        }

        // Line: Flush
        log.info(prefix + "Flush  │ AI msg + {} tool results → transcript", rec.toolResultCount);

        // Lines: Stop events
        for (TurnRecord.StopEvent event : rec.stopEvents) {
            String detail = switch (event.type()) {
                case REQUESTED -> "requested: " + event.category() + " │ " + event.message() + " │ grace=" + event.graceRemaining();
                case ESCALATED -> "escalated: " + event.category() + " │ " + event.message();
                case GRACE_CONSUMED -> "grace consumed (" + event.message() + ") │ remaining=" + event.graceRemaining();
            };
            log.warn(prefix + "STOP   │ {}", detail);
        }

        // Footer: Done summary
        StringBuilder done = new StringBuilder(128);
        done.append("Turn ").append(rec.turnNumber).append(" done");
        done.append(" │ tools: ").append(rec.okCount).append(" ok / ").append(rec.failCount).append(" fail");
        done.append(" │ Δ+").append(rec.turnDeltaTokens).append(" tok");
        done.append(" │ total ").append(rec.usedTokens);
        done.append(" │ water ").append(String.format("%.0f", rec.waterRatio * 100)).append("%");
        if (rec.finished) done.append(" │ ✓ finish");
        log.info(footer + done);
    }

    // ===== Agent 级别日志（立即打印，不属于单轮 turn）=====

    public void agentStart(SessionState state) {
        log.info("┌─ EonAgent 启动");
        log.info("├─ session: {} │ maxSteps: {} │ budget: {} tokens",
                state.getSessionId(), config.getLoop().maxSteps, config.getBudget().getMaxTokens());
        log.info("├─ 用户请求: {}", state.getUserOriginalInput());
        log.info("└─");
    }

    public void agentFinish(SessionState state) {
        log.info("┌─ EonAgent 完成 │ turns={} │ tokens={} │ ✓ finish",
                state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        log.info("└─");
    }

    public void stopForced(String category, int turns, int tokens) {
        log.warn("┌─ ⚠ 强制终止: {} │ turns={} │ tokens={}", category, turns, tokens);
        log.info("└─");
    }

    // ===== 工具方法 =====

    /** 提取工具调用的关键参数摘要，用于日志展示。 */
    public String summarizeArgs(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        String summary = switch (toolName) {
            case "web_search" -> {
                Object q = args.get("query");
                yield q != null ? "{query: \"" + truncate(String.valueOf(q), 60) + "\"}" : args.toString();
            }
            case "web_read", "download" -> {
                Object u = args.get("url");
                yield u != null ? "{url: \"" + truncate(String.valueOf(u), 60) + "\"}" : args.toString();
            }
            case "finish" -> {
                Object g = args.get("goal_achieved");
                yield "{goal_achieved: " + g + "}";
            }
            case "todo_write" -> {
                Object t = args.get("todos");
                int count = (t instanceof List<?> l) ? l.size() : 0;
                yield "{todos: " + count + " items}";
            }
            case "file_io" -> {
                Object op = args.get("operation");
                Object p = args.get("path");
                yield "{op: " + op + ", path: \"" + truncate(String.valueOf(p), 50) + "\"}";
            }
            case "enable_tools" -> {
                Object t = args.get("tools");
                yield "{tools: " + t + "}";
            }
            default -> formatArgs(args);
        };
        return truncate(summary, 80);
    }

    /** 将 Map<String,Object> 格式化为 {key: value, key: value} 风格，替代 Java 默认的 {key=value}。 */
    private String formatArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : args.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
