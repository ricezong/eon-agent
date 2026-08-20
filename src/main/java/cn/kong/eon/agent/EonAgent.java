package cn.kong.eon.agent;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.agent.profile.PolicyRouter;
import cn.kong.eon.agent.profile.RequestProfile;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Agent 单入口统一引擎。
 *
 * Core Loop: 路由 → 组装上下文 → PreModel → build → 调用 LLM → 解析 → 返回/扩展
 * Extension Loop: PreTool → 执行工具 → PostTool → 回填 → 回到 Core Loop
 *
 * System Prompt 完全冻结（basePrompt），tool_catalog 作为独立消息注入，保证 KV Cache 前缀稳定。
 * Profile 两档：SIMPLE（默认）、TASK（todo_write 后升级，TodoNavigator 激活）。两者均走两阶段懒加载。
 *
 * Hook 调度：按执行阶段分组（PreModel/PostModel/PreTool/PostTool），用 instanceof 过滤，
 * 同阶段内按 order() 升序执行。
 *
 * 优雅停止机制：
 *   Hook 通过 HookResult.stop(StopReason) 请求停止。
 *   EonAgent 收到后注入收尾 nudge，给 LLM graceSteps 轮调用 finish 总结的机会。
 *   超过 graceSteps 仍未 finish，则用 formatForcedTermination 做硬终止（含进度信息）。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    private static final String ENABLE_TOOLS = "enable_tools";
    private static final String FINISH = "finish";

    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolResultRenderer resultRenderer;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final PolicyRouter policyRouter;
    private final List<Hook> hooks;
    private final ToolContext toolContext;

    public EonAgent(AgentConfig config,
                    LlmClient llmClient,
                    ToolRegistry toolRegistry,
                    ToolResultRenderer resultRenderer,
                    JsonlStore jsonlStore,
                    String basePrompt,
                    ToolContext toolContext) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.resultRenderer = resultRenderer;
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.policyRouter = new PolicyRouter();
        this.hooks = new ArrayList<>();
        this.toolContext = toolContext;
    }

    public void addHook(Hook hook) {
        hooks.add(hook);
        log.debug("Hook added: {}", hook.name());
    }

    public int getHookCount() {
        return hooks.size();
    }

    /** 按 order 升序排序，每次调用创建新列表。 */
    private List<Hook> getSortedHooks() {
        List<Hook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparingInt(Hook::order));
        return sorted;
    }

    /** 运行 Agent 主循环。 */
    public String run(SessionState state) {
        log.info("║ EonAgent 启动 ║ session={} ║ maxSteps={} ║ budget={} tokens ║",
                state.getSessionId(), config.getLoop().maxSteps, config.getBudget().getMaxTokens());
        log.info("║ 用户请求: {}", state.getUserOriginalInput());

        // 初始化优雅停止状态
        state.setStopState(SessionState.StopState.none());

        // 重置懒加载状态
        state.setPendingToolMounts(null);

        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));

        while (state.getTurnCount() < config.getLoop().maxSteps
                || (state.isStopRequested() && state.getTurnCount() < config.getLoop().absoluteMaxSteps)) {
            state.incrementTurn();

            int turnStartTokens = state.getUsageAccum().getTotalTokens();

            RequestProfile profile = policyRouter.route(state);
            long usedTokens = state.getUsageAccum().getTotalTokens();
            long maxBudget = config.getBudget().getMaxTokens();
            double ratio = maxBudget > 0 ? (double) usedTokens / maxBudget : 0.0;
            String stopSuffix = state.isStopRequested()
                    ? " | stop: " + state.getStopState().getReason().getCategory()
                    + "(grace=" + state.getStopState().getRemainingGraceSteps() + ")"
                    : "";
            log.info("========== Turn {} [{}] | tokens: {}/{} ({}%){} ==========",
                    state.getTurnCount(), profile, usedTokens, maxBudget,
                    String.format("%.0f", ratio * 100), stopSuffix);

            try {
                // ===== Core Loop =====

                // 1. 组装上下文
                ContextBuilder ctx = buildContext(state, profile);

                // 2. PreModel 阶段
                List<Hook> sortedHooks = getSortedHooks();
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PreModelHook pmh) {
                        HookResult r = pmh.beforeModelCall(state, ctx);
                        if (r.isStop()) {
                            String stopResult = handleStop(state, r.getStopReason());
                            if (stopResult != null) return stopResult;
                            continue; // stop 已注入 nudge，继续循环让 LLM 调用 finish
                        }
                    }
                }

                // 3. 构建最终 messages
                List<ChatMessage> messages = ctx.build();
                state.setCurrentMessages(messages);
                log.info("[Context] {} messages | est. {} tokens | summary: {} | catalog: {} tools",
                        messages.size(), ctx.estimateTokens(),
                        state.getCompressionState().getLastSummary() != null ? "yes" : "no",
                        toolRegistry.getAllToolNames().size());

                // 4. 获取工具 Schema
                List<ToolSpecification> tools = getToolsForProfile(state);

                // 4.1 stop 期间强制挂载 finish（如果尚未挂载）
                if (state.isStopRequested()) {
                    tools = ensureFinishMounted(tools);
                }

                // 5. 调用模型
                LlmResponse response = llmClient.chat(messages, tools);
                state.setLastResponse(response);
                int deltaTokens = response.usage() != null ? response.usage().getTotalTokens() : 0;
                state.getUsageAccum().add(response.usage());

                // 6. 解析输出
                String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
                state.setLastAssistantText(thought);

                List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();

                // LLM 响应日志
                String thoughtSummary = thought.length() > 100 ? thought.substring(0, 100) + "..." : thought;
                if (requests != null && !requests.isEmpty()) {
                    List<String> toolNames = requests.stream().map(ToolExecutionRequest::name).toList();
                    log.info("[LLM] thought: \"{}\" | tools: {} | tokens: +{} ({} total)",
                            thoughtSummary, toolNames, deltaTokens, state.getUsageAccum().getTotalTokens());
                } else {
                    log.info("[LLM] thought: \"{}\" | no tools | tokens: +{} ({} total)",
                            thoughtSummary, deltaTokens, state.getUsageAccum().getTotalTokens());
                }

                if (requests == null || requests.isEmpty()) {
                    // 已声明工具但未调用：提醒模型
                    if (state.getPendingToolMounts() != null) {
                        state.addFormatCorrection(
                                "你已获得工具的完整调用参数但未调用。如果任务需要工具，请直接调用；如果不需要，请直接回答。");
                        finalizeAndAppend(state);
                        continue;
                    }

                    // stop 期间 LLM 未调用 finish：消耗 grace step
                    if (state.isStopRequested()) {
                        boolean hasMore = state.getStopState().consumeGraceStep();
                        log.warn("[STOP] grace consumed (LLM did not call finish) | remaining: {}",
                                state.getStopState().getRemainingGraceSteps());
                        if (!hasMore) {
                            // grace 用尽，硬终止
                            return formatForcedTermination(state, state.getStopState().getReason());
                        }
                        // 注入更强的提醒
                        state.addFormatCorrection(
                                "请立即调用 finish 工具进行总结。这是最后的机会，否则任务将被强制终止。");
                        finalizeAndAppend(state);
                        continue;
                    }

                    // 聊天结束
                    finalizeAndAppend(state);
                    return thought;
                }

                // 校验工具是否存在
                for (ToolExecutionRequest req : requests) {
                    if (!toolRegistry.contains(req.name())) {
                        state.getFormatCorrections().add("工具 " + req.name() + " 不存在，请使用可用工具。");
                    }
                }
                state.setPendingToolCalls(requests);

                // 7. PostModel 阶段
                boolean postModelStop = false;
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PostModelHook pmh) {
                        HookResult r = pmh.afterModelCall(state, response);
                        if (r.isStop()) {
                            String stopResult = handleStop(state, r.getStopReason());
                            if (stopResult != null) return stopResult;
                            postModelStop = true;
                            break;
                        }
                    }
                }
                if (postModelStop) {
                    // stop 请求已注入 nudge，跳过工具执行，回填后继续循环
                    finalizeIfPending(state);
                    continue;
                }

                // ===== Extension Loop =====

                // 8. PreTool 阶段
                boolean preToolStop = false;
                for (Hook hook : sortedHooks) {
                    if (!hook.isActive(state)) continue;
                    if (hook instanceof Hook.PreToolHook pth) {
                        HookResult r = pth.beforeToolExecution(state, requests);
                        if (r.isStop()) {
                            String stopResult = handleStop(state, r.getStopReason());
                            if (stopResult != null) return stopResult;
                            preToolStop = true;
                            break;
                        }
                    }
                }
                if (preToolStop) {
                    // stop 请求已注入 nudge，跳过工具执行，回填后继续循环
                    finalizeIfPending(state);
                    continue;
                }

                // 9. 执行工具
                List<ToolExecutionResult> results = executeTools(state);

                // 10. PostTool 阶段
                for (int i = 0; i < requests.size(); i++) {
                    ToolExecutionRequest req = requests.get(i);
                    ToolExecutionResult result = results.get(i);
                    boolean success = !result.content().startsWith("[ERROR]");
                    boolean postToolStop = false;
                    for (Hook hook : sortedHooks) {
                        if (!hook.isActive(state)) continue;
                        if (hook instanceof Hook.PostToolHook pth) {
                            HookResult r = pth.afterToolExecution(state, req.name(), success);
                            if (r.isStop()) {
                                String stopResult = handleStop(state, r.getStopReason());
                                if (stopResult != null) return stopResult;
                                postToolStop = true;
                                break;
                            }
                        }
                    }
                    if (postToolStop) break;
                }

                // 11. 回填
                int toolCount = results.size();
                int okCount = (int) results.stream().filter(r -> !r.content().startsWith("[ERROR]")).count();
                int failCount = toolCount - okCount;
                finalizeAndAppend(state);
                int turnDeltaTokens = state.getUsageAccum().getTotalTokens() - turnStartTokens;
                double waterRatio = maxBudget > 0 ? (double) state.getUsageAccum().getTotalTokens() / maxBudget : 0.0;
                log.info("---------- Turn {} done | tools: {} ok, {} fail | delta: +{} tokens | total: {} | water: {}% ----------",
                        state.getTurnCount(), okCount, failCount, turnDeltaTokens,
                        state.getUsageAccum().getTotalTokens(), String.format("%.0f", waterRatio * 100));

                // finish 检测
                if (state.isFinished()) {
                    log.info("║ EonAgent 完成 ║ turns={} ║ tokens={} ║ finish=true ║",
                            state.getTurnCount(), state.getUsageAccum().getTotalTokens());
                    return state.getLastAssistantText();
                }

                // stop 期间 LLM 调用了非 finish 工具：消耗 grace step
                if (state.isStopRequested()) {
                    boolean hasMore = state.getStopState().consumeGraceStep();
                    log.warn("[STOP] grace consumed (LLM called non-finish tool) | remaining: {}",
                            state.getStopState().getRemainingGraceSteps());
                    if (!hasMore) {
                        return formatForcedTermination(state, state.getStopState().getReason());
                    }
                }

            } catch (Exception e) {
                log.error("Agent loop unexpected error: {}", e.getMessage(), e);
                // 异常也走优雅停止流程
                StopReason reason = new StopReason(
                        StopCategory.UNEXPECTED_ERROR,
                        e.getMessage(),
                        config.getBudget().getGraceSteps());
                String stopResult = handleStop(state, reason);
                if (stopResult != null) return stopResult;
                continue; // 继续循环让 LLM 调用 finish
            }
        }

        log.warn("[STOP] max steps reached: {}", config.getLoop().maxSteps);
        StopReason reason = new StopReason(
                StopCategory.MAX_STEPS_REACHED,
                "达到最大步数限制 (" + config.getLoop().maxSteps + ")",
                config.getBudget().getGraceSteps());
        String stopResult = handleStop(state, reason);
        if (stopResult != null) return stopResult;
        return formatForcedTermination(state, reason);
    }

    // ===== 优雅停止子流程 =====

    /**
     * 处理 stop 请求：注入收尾 nudge，进入 grace period。
     * 如果 graceSteps=0，直接硬终止。
     * 如果已经处于 stop 中（升级原因），更新 reason 但不重置 grace steps。
     */
    private String handleStop(SessionState state, StopReason reason) {
        if (reason.getGraceSteps() <= 0) {
            // 无 grace，直接硬终止
            return formatForcedTermination(state, reason);
        }

        // 首次 stop：设置状态 + 注入收尾 nudge
        if (!state.isStopRequested()) {
            state.getStopState().request(reason);
            state.addNudge(reason.toNudgeText());
            log.warn("[STOP] requested: {} | msg: {} | grace: {}",
                    reason.getCategory(), reason.getMessage(), reason.getGraceSteps());

            // 回填当前状态（如果有未提交的临时数据）
            finalizeIfPending(state);

            // 继续循环，让 LLM 有机会调用 finish
            return null;  // null 表示继续循环
        } else {
            // 已经在 stop 中，新的 stop 请求：
            // 如果 grace steps 已经用尽，硬终止
            if (state.getStopState().getRemainingGraceSteps() <= 0) {
                return formatForcedTermination(state, reason);
            }
            // 否则更新 reason 并注入更强的 nudge
            state.addNudge(reason.toNudgeText());
            log.warn("[STOP] escalated: {} | msg: {}", reason.getCategory(), reason.getMessage());
            finalizeIfPending(state);
            return null;  // 继续循环
        }
    }

    /** 如果有未提交的 AI 消息/工具结果，先回填到 JSONL。 */
    private void finalizeIfPending(SessionState state) {
        if (state.getPendingToolCalls() != null || state.getLastToolResults() != null) {
            finalizeAndAppend(state);
        }
    }

    /**
     * 硬终止：构建包含原因、进度、待做任务的结构化输出。
     */
    private String formatForcedTermination(SessionState state, StopReason reason) {
        log.warn("[STOP] forced termination: {} | turns={} | tokens={}",
                reason.getCategory(), state.getTurnCount(), state.getUsageAccum().getTotalTokens());

        StringBuilder sb = new StringBuilder();

        // 1. 终止原因
        sb.append("════════════════════════════════════════\n");
        sb.append("  任务终止: ").append(reason.getCategory().getDisplayName()).append("\n");
        sb.append("  原因: ").append(reason.getMessage()).append("\n");
        sb.append("════════════════════════════════════════\n\n");

        // 2. 任务进度
        List<cn.kong.eon.model.TodoItem> todos = toolContext.todoStore().getAll();
        if (!todos.isEmpty()) {
            long completed = todos.stream()
                    .filter(t -> t.getStatus() == cn.kong.eon.model.TodoStatus.COMPLETED).count();
            long inProgress = todos.stream()
                    .filter(t -> t.getStatus() == cn.kong.eon.model.TodoStatus.IN_PROGRESS).count();
            long pending = todos.stream()
                    .filter(t -> t.getStatus() == cn.kong.eon.model.TodoStatus.PENDING).count();
            long blocked = todos.stream()
                    .filter(t -> t.getStatus() == cn.kong.eon.model.TodoStatus.BLOCKED).count();

            sb.append("【任务进度】").append(completed).append("/").append(todos.size())
                    .append(" 已完成（").append(inProgress).append(" 进行中, ")
                    .append(pending).append(" 待办, ").append(blocked).append(" 阻塞）\n\n");

            sb.append("【任务清单】\n");
            for (cn.kong.eon.model.TodoItem t : todos) {
                sb.append("  ").append(t.toString()).append("\n");
            }
            sb.append("\n");

            // 3. 待做任务
            List<cn.kong.eon.model.TodoItem> todoPending = todos.stream()
                    .filter(t -> t.getStatus() != cn.kong.eon.model.TodoStatus.COMPLETED
                            && t.getStatus() != cn.kong.eon.model.TodoStatus.CANCELLED)
                    .toList();
            if (!todoPending.isEmpty()) {
                sb.append("【待做任务】\n");
                for (cn.kong.eon.model.TodoItem t : todoPending) {
                    sb.append("  - ").append(t.getContent());
                    if (t.getStatus() == cn.kong.eon.model.TodoStatus.BLOCKED && t.getBlockReason() != null) {
                        sb.append(" [阻塞原因: ").append(t.getBlockReason()).append("]");
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("【任务进度】无 Todo 列表（任务未通过 todo_write 建立计划）\n");
            sb.append("已执行轮次: ").append(state.getTurnCount()).append("\n\n");
        }

        // 4. 关键发现
        List<String> insights = state.getInsights();
        if (insights != null && !insights.isEmpty()) {
            sb.append("【关键发现】\n");
            int idx = 1;
            for (String insight : insights) {
                sb.append("  ").append(idx++).append(". ").append(insight).append("\n");
            }
            sb.append("\n");
        }

        // 5. Token 消耗
        sb.append("【消耗统计】总 Token: ").append(state.getUsageAccum().getTotalTokens())
                .append(" (prompt=").append(state.getUsageAccum().getPromptTokens())
                .append(", completion=").append(state.getUsageAccum().getCompletionTokens())
                .append(")\n");
        sb.append("执行轮次: ").append(state.getTurnCount()).append("\n");

        return sb.toString();
    }

    /** stop 期间确保 finish 工具已挂载。 */
    private List<ToolSpecification> ensureFinishMounted(List<ToolSpecification> tools) {
        boolean hasFinish = tools.stream().anyMatch(t -> FINISH.equals(t.name()));
        if (hasFinish) return tools;

        List<ToolSpecification> augmented = new ArrayList<>(tools);
        List<ToolSpecification> finishSpec = toolRegistry.getSpecificationsByName(Set.of(FINISH));
        augmented.addAll(finishSpec);
        log.info("[STOP] finish tool force-mounted ({} -> {} specs)", tools.size(), augmented.size());
        return augmented;
    }

    /** 组装上下文。 */
    private ContextBuilder buildContext(SessionState state, RequestProfile profile) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setSystemPrompt(basePrompt);

        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }

        List<ChatMessage> transcript = jsonlStore.readAll();
        ctx.setTranscript(transcript);

        ctx.setToolCatalog(toolRegistry.getCatalogSummary());

        return ctx;
    }

    /**
     * 两阶段懒加载获取工具 Schema
     * 未声明：只挂载 enable_tools
     * 已声明：按声明的工具名挂载（排除 enable_tools 自身）
     */
    private List<ToolSpecification> getToolsForProfile(SessionState state) {
        Set<String> mounts = state.getPendingToolMounts();
        if (mounts == null || mounts.isEmpty()) {
            log.info("[Mount] lazy-load phase 1: enable_tools only");
            return toolRegistry.getSpecificationsByName(Set.of(ENABLE_TOOLS));
        }

        // 第二轮：挂载模型声明的工具（排除 enable_tools 自身）
        Set<String> realMounts = new LinkedHashSet<>(mounts);
        realMounts.remove(ENABLE_TOOLS);
        List<ToolSpecification> specs = toolRegistry.getSpecificationsByName(realMounts);
        log.info("[Mount] lazy-load phase 2: {} -> {} specs", realMounts, specs.size());
        return specs;
    }

    /** 执行工具。 */
    private List<ToolExecutionResult> executeTools(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        List<ToolExecutionResult> results = new ArrayList<>();

        for (ToolExecutionRequest req : requests) {
            Map<String, Object> args = parseArgs(req.arguments());
            String reason = (String) args.get("reason");

            String rawResult = toolRegistry.execute(req.name(), args, state, toolContext);

            // 拦截 enable_tools：从参数中提取工具名，设置 pendingToolMounts
            if (ENABLE_TOOLS.equals(req.name())) {
                Object toolsRaw = args.get("tools");
                if (toolsRaw instanceof List<?> list) {
                    Set<String> declaredTools = new LinkedHashSet<>();
                    for (Object item : list) {
                        String name = String.valueOf(item).trim();
                        if (toolRegistry.contains(name)) {
                            declaredTools.add(name);
                        } else {
                            log.warn("[Mount] declared tool not found: {}", name);
                        }
                    }
                    if (!declaredTools.isEmpty()) {
                        state.setPendingToolMounts(declaredTools);
                        log.info("[Mount] declared: {} -> {} valid tools", toolsRaw, declaredTools);
                    }
                }
            }

            String rendered = resultRenderer.render(req.name(), req.id(), reason, rawResult, state);

            boolean success = !rawResult.startsWith("[ERROR]");
            String argsSummary = summarizeArgs(req.name(), args);
            log.info("[Tool] {} -> {} | args: {} | {} chars",
                    req.name(), success ? "OK" : "FAIL", argsSummary, rendered.length());

            results.add(ToolExecutionResult.of(req.id(), req.name(), rendered));

            if (FINISH.equals(req.name()) && state.isFinished()) {
                break;
            }
        }
        state.setLastToolResults(results);
        return results;
    }

    /** 回填 AI 消息和工具结果到 JSONL，清理临时状态。 */
    private void finalizeAndAppend(SessionState state) {
        AiMessage aiMsg = state.getLastAssistantText() != null && !state.getLastAssistantText().isBlank()
                ? AiMessage.from(state.getLastAssistantText(), state.getPendingToolCalls())
                : AiMessage.from(state.getPendingToolCalls());
        jsonlStore.append(aiMsg);

        List<ToolExecutionResult> toolResults = state.getLastToolResults();
        if (toolResults != null) {
            for (ToolExecutionResult result : toolResults) {
                jsonlStore.append(ToolExecutionResultMessage.from(
                        result.toolCallId(), result.toolName(), result.content()));
            }
            log.info("[Flush] AI msg + {} tool results appended to transcript", toolResults.size());
        } else {
            log.info("[Flush] AI msg appended to transcript");
        }

        state.getPendingNudges().clear();
        state.getFormatCorrections().clear();
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
    }

    /** 提取工具调用的关键参数摘要，用于日志展示。 */
    private String summarizeArgs(String toolName, Map<String, Object> args) {
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
            default -> args.toString();
        };
        return truncate(summary, 80);
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper()
                    .readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[Tool] failed to parse arguments: {}", json, e);
            return Map.of();
        }
    }
}
