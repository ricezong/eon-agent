package cn.kong.eon.agent;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.agent.profile.PolicyRouter;
import cn.kong.eon.agent.profile.RequestProfile;
import cn.kong.eon.agent.support.HookDispatcher;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.agent.support.ToolExecutionHandler;
import cn.kong.eon.agent.support.TurnLogger;
import cn.kong.eon.agent.support.TurnRecord;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TerminationFormatter;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Agent 单入口统一引擎。
 *
 * Core Loop: 路由 → 组装上下文 → PreModel → 调用 LLM → 解析 → 返回/扩展
 * Extension Loop: PreTool → 执行工具 → PostTool → 回填 → 回到 Core Loop
 *
 * 职责委托：
 *   - 日志输出 → {@link TurnLogger}（收集式：每步写入 TurnRecord，turn 结束统一 flush）
 *   - 工具执行 → {@link ToolExecutionHandler}
 *   - 终止格式化 → {@link TerminationFormatter}
 *   - Hook 调度 → {@link HookDispatcher}
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    private static final String ENABLE_TOOLS = "enable_tools";
    private static final String FINISH = "finish";

    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final ToolContext toolContext;

    private final PolicyRouter policyRouter = new PolicyRouter();
    private final TurnLogger logger;
    private final ToolExecutionHandler toolHandler;
    private final TerminationFormatter terminationFormatter = new TerminationFormatter();

    // Hook 预分组：按阶段分别维护有序列表
    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>();
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();
    private int totalHookCount = 0;

    /** 当前 Turn 的日志收集器。非 null 时表示正在执行 turn，null 表示在 turn 之外。 */
    private TurnRecord currentRec;

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
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.toolContext = toolContext;
        this.logger = new TurnLogger(config);
        this.toolHandler = new ToolExecutionHandler(toolRegistry, resultRenderer, toolContext, logger);
    }

    public void addHook(Hook hook) {
        boolean added = false;
        if (hook instanceof Hook.PreModelHook h) {
            preModelHooks.add(h);
            preModelHooks.sort(Comparator.comparingInt(Hook::order));
            added = true;
        }
        if (hook instanceof Hook.PostModelHook h) {
            postModelHooks.add(h);
            postModelHooks.sort(Comparator.comparingInt(Hook::order));
            added = true;
        }
        if (hook instanceof Hook.PreToolHook h) {
            preToolHooks.add(h);
            preToolHooks.sort(Comparator.comparingInt(Hook::order));
            added = true;
        }
        if (hook instanceof Hook.PostToolHook h) {
            postToolHooks.add(h);
            postToolHooks.sort(Comparator.comparingInt(Hook::order));
            added = true;
        }
        if (added) {
            totalHookCount++;
            log.debug("Hook added: {}", hook.name());
        }
    }

    public int getHookCount() {
        return totalHookCount;
    }

    // ===== 主循环 =====

    /** 运行 Agent 主循环。 */
    public String run(SessionState state) {
        initRun(state);

        while (shouldContinue(state)) {
            state.incrementTurn();
            int turnStartTokens = state.getUsageAccum().getTotalTokens();

            try {
                String result = executeTurn(state, turnStartTokens);
                if (result != null) return result;
            } catch (Exception e) {
                String stopResult = handleLoopException(state, e);
                if (stopResult != null) return stopResult;
            }
        }

        return handleMaxSteps(state);
    }

    private void initRun(SessionState state) {
        logger.agentStart(state);
        state.setStopState(SessionState.StopState.none());
        state.setPendingToolMounts(null);
        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));
    }

    private boolean shouldContinue(SessionState state) {
        return state.getTurnCount() < config.getLoop().maxSteps
                || (state.isStopRequested() && state.getTurnCount() < config.getLoop().absoluteMaxSteps);
    }

    /**
     * 执行单个 Turn，返回非 null 表示应退出循环。
     * 每一步将日志信息写入 TurnRecord，turn 结束时统一 flush。
     */
    private String executeTurn(SessionState state, int turnStartTokens) {
        TurnRecord rec = logger.newRecord();
        this.currentRec = rec;

        RequestProfile profile = policyRouter.route(state);
        logger.turnHeader(rec, state, profile);

        // 1. 组装上下文 + PreModel Hooks
        ContextBuilder ctx = buildContext(state);
        FireResult preModel = firePreModelHooks(state, ctx);
        if (preModel.exitResult() != null) { flushTurn(rec); return preModel.exitResult(); }

        // 2. 构建 messages + 获取工具 Schema
        List<ChatMessage> messages = ctx.build();
        state.setCurrentMessages(messages);
        logger.contextInfo(rec, ctx, messages, state, toolRegistry.getAllToolNames().size());

        List<ToolSpecification> tools = getToolsForProfile(rec, state);
        if (state.isStopRequested()) tools = ensureFinishMounted(tools);

        // 3. 调用 LLM
        LlmResponse response = llmClient.chat(messages, tools);
        state.setLastResponse(response);
        int deltaTokens = response.usage() != null ? response.usage().getTotalTokens() : 0;
        state.getUsageAccum().add(response.usage());

        String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
        state.setLastAssistantText(thought);
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        logger.llmResponse(rec, thought, requests, deltaTokens, state);

        // 4. 无工具调用
        if (requests == null || requests.isEmpty()) {
            String result = handleNoToolCalls(rec, state, thought);
            flushTurn(rec);
            return result;
        }

        // 5. PostModel Hooks (循环检测等)
        validateToolExistence(state, requests);
        state.setPendingToolCalls(requests);
        FireResult postModel = firePostModelHooks(state, response);
        if (postModel.exitResult() != null) { flushTurn(rec); return postModel.exitResult(); }
        if (postModel.skipped()) { flushTurn(rec); return null; }

        // 6. Extension Loop: PreTool → Execute → PostTool
        FireResult extension = executeExtensionLoop(rec, state, requests);
        if (extension.exitResult() != null) { flushTurn(rec); return extension.exitResult(); }

        // 7. 回填 + finish 检测
        finalizeAndAppend(rec, state);
        logger.turnDone(rec, state, turnStartTokens);

        if (state.isFinished()) {
            flushTurn(rec);
            logger.agentFinish(state);
            return state.getLastAssistantText();
        }

        // 8. stop 期间消耗 grace（非 finish 工具 / 无工具调用 两种场景统一处理）
        if (state.isStopRequested()) {
            String graceResult = consumeGraceStep(rec, state, "LLM called non-finish tool");
            flushTurn(rec);
            return graceResult;
        }

        flushTurn(rec);
        return null;
    }

    /** flush 当前 turn 日志并清理引用。 */
    private void flushTurn(TurnRecord rec) {
        logger.flush(rec);
        this.currentRec = null;
    }

    /** Extension Loop: PreTool → Execute → PostTool */
    private FireResult executeExtensionLoop(TurnRecord rec, SessionState state, List<ToolExecutionRequest> requests) {
        FireResult preTool = firePreToolHooks(state, requests);
        if (preTool.exitResult() != null) return preTool;
        if (preTool.skipped()) return FireResult.cont();

        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            boolean success = !result.content().startsWith("[ERROR]");
            FireResult postTool = firePostToolHooks(state, requests.get(i).name(), success);
            if (postTool.exitResult() != null) return postTool;
            if (postTool.skipped()) break;
        }

        return FireResult.cont();
    }

    /** 处理无工具调用的情况。 */
    private String handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        // 截断检测
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            logger.outputTruncated(rec);
            state.addFormatCorrection(
                    "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。");
            finalizeAndAppend(rec, state);
            return null;
        }

        // 已声明工具但未调用：提醒模型
        if (state.getPendingToolMounts() != null) {
            state.addFormatCorrection(
                    "你已获得工具的完整调用参数但未调用。如果任务需要工具，请直接调用；如果不需要，请直接回答。不要重复调用 enable_tools。");
            finalizeAndAppend(rec, state);
            return null;
        }

        // stop 期间 LLM 未调 finish：消耗 grace step
        if (state.isStopRequested()) {
            String graceResult = consumeGraceStep(rec, state, "LLM did not call finish");
            if (graceResult != null) return graceResult;
            state.addFormatCorrection(
                    "请立即调用 finish 工具进行总结。这是最后的机会，否则任务将被强制终止。");
            finalizeAndAppend(rec, state);
            return null;
        }

        // 正常聊天结束
        finalizeAndAppend(rec, state);
        return thought;
    }

    // ===== 优雅停止子流程 =====

    /**
     * 消耗一个 grace step。返回非 null 表示应退出循环（硬终止）。
     * 统一处理两种场景：LLM 未调用任何工具 / LLM 调用了非 finish 工具。
     */
    private String consumeGraceStep(TurnRecord rec, SessionState state, String reason) {
        boolean hasMore = state.getStopState().consumeGraceStep();
        logger.graceConsumed(rec, reason, state.getStopState().getRemainingGraceSteps());
        if (!hasMore) {
            return forceTerminate(state, state.getStopState().getReason());
        }
        return null;
    }

    private String handleLoopException(SessionState state, Exception e) {
        log.error("Agent loop unexpected error: {}", e.getMessage(), e);
        // 异常发生在 turn 内部，currentRec 可能已创建
        TurnRecord rec = this.currentRec;
        if (rec != null) {
            return handleStop(rec, state, new StopReason(
                    StopCategory.UNEXPECTED_ERROR, e.getMessage(), config.getBudget().getGraceSteps()));
        }
        // turn 外部异常，直接处理
        return handleStop(null, state, new StopReason(
                StopCategory.UNEXPECTED_ERROR, e.getMessage(), config.getBudget().getGraceSteps()));
    }

    private String handleMaxSteps(SessionState state) {
        log.warn("[STOP] max steps reached: {}", config.getLoop().maxSteps);
        StopReason reason = new StopReason(
                StopCategory.MAX_STEPS_REACHED,
                "达到最大步数限制 (" + config.getLoop().maxSteps + ")",
                config.getBudget().getGraceSteps());
        // maxSteps 在 while 循环外触发，无 TurnRecord
        String stopResult = handleStop(null, state, reason);
        return stopResult != null ? stopResult : forceTerminate(state, reason);
    }

    /**
     * 处理 stop 请求：注入收尾 nudge，进入 grace period。
     * graceSteps=0 直接硬终止；已在 stop 中则升级原因但不重置 grace。
     *
     * @param rec 当前 TurnRecord，null 表示在 turn 之外（maxSteps/异常等场景），stop 事件立即打印
     */
    private String handleStop(TurnRecord rec, SessionState state, StopReason reason) {
        if (reason.getGraceSteps() <= 0) {
            return forceTerminate(state, reason);
        }

        if (!state.isStopRequested()) {
            state.getStopState().request(reason);
            state.addNudge(reason.toNudgeText());
            if (rec != null) {
                logger.stopRequested(rec, reason.getCategory(), reason.getMessage(), reason.getGraceSteps());
            } else {
                log.warn("[STOP] requested: {} | msg: {} | grace: {}",
                        reason.getCategory(), reason.getMessage(), reason.getGraceSteps());
            }
            finalizeIfPending(rec, state);
            return null;
        }

        // 已在 stop 中，升级原因
        if (state.getStopState().getRemainingGraceSteps() <= 0) {
            return forceTerminate(state, reason);
        }
        state.addNudge(reason.toNudgeText());
        if (rec != null) {
            logger.stopEscalated(rec, reason.getCategory(), reason.getMessage());
        } else {
            log.warn("[STOP] escalated: {} | msg: {}", reason.getCategory(), reason.getMessage());
        }
        finalizeIfPending(rec, state);
        return null;
    }

    private String forceTerminate(SessionState state, StopReason reason) {
        logger.stopForced(reason.getCategory().name(), state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return terminationFormatter.formatForcedTermination(
                reason.getCategory().getDisplayName(), reason.getMessage(),
                toolContext.todoStore().getAll(), state.getInsights(),
                state.getTurnCount(), state.getUsageAccum().getTotalTokens(),
                state.getUsageAccum().getPromptTokens(), state.getUsageAccum().getCompletionTokens());
    }

    private void finalizeIfPending(TurnRecord rec, SessionState state) {
        if (state.getPendingToolCalls() != null || state.getLastToolResults() != null) {
            finalizeAndAppend(rec, state);
        }
    }

    // ===== Hook 调度（统一委托 HookDispatcher）=====

    private FireResult firePreModelHooks(SessionState state, ContextBuilder ctx) {
        // PreModel 的 stop 语义：handleStop 返回 null 后继续遍历后续 hook
        // （BudgetHook stop 后，NudgeRenderHook/ContextCompactHook 仍需执行）
        return HookDispatcher.dispatch(
                preModelHooks, state,
                (hook, s) -> hook.beforeModelCall(s, ctx),
                reason -> handleStop(currentRec, state, reason),
                () -> {},  // PreModel 不需要 finalize（LLM 还没调用，无 pending 数据）
                true       // continueAfterStop: stop 后继续遍历
        );
    }

    private FireResult firePostModelHooks(SessionState state, LlmResponse response) {
        return HookDispatcher.dispatch(
                postModelHooks, state,
                (hook, s) -> hook.afterModelCall(s, response),
                reason -> handleStop(currentRec, state, reason),
                () -> finalizeIfPending(currentRec, state)
        );
    }

    private FireResult firePreToolHooks(SessionState state, List<ToolExecutionRequest> requests) {
        return HookDispatcher.dispatch(
                preToolHooks, state,
                (hook, s) -> hook.beforeToolExecution(s, requests),
                reason -> handleStop(currentRec, state, reason),
                () -> finalizeIfPending(currentRec, state)
        );
    }

    private FireResult firePostToolHooks(SessionState state, String toolName, boolean success) {
        return HookDispatcher.dispatch(
                postToolHooks, state,
                (hook, s) -> hook.afterToolExecution(s, toolName, success),
                reason -> handleStop(currentRec, state, reason),
                () -> {}  // PostTool stop 不 finalize，由外层 finalizeAndAppend 处理
        );
    }

    // ===== 上下文与工具方法 =====

    private ContextBuilder buildContext(SessionState state) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setSystemPrompt(basePrompt);
        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }
        ctx.setTranscript(jsonlStore.snapshot());
        ctx.setToolCatalog(toolRegistry.getCatalogSummary());
        return ctx;
    }

    /** 两阶段懒加载：未声明只挂 enable_tools；已声明挂载声明工具 + enable_tools。 */
    private List<ToolSpecification> getToolsForProfile(TurnRecord rec, SessionState state) {
        Set<String> mounts = state.getPendingToolMounts();
        if (mounts == null || mounts.isEmpty()) {
            logger.mountPhase(rec, 1, "enable_tools only");
            return toolRegistry.getSpecificationsByName(Set.of(ENABLE_TOOLS));
        }
        Set<String> realMounts = new LinkedHashSet<>(mounts);
        realMounts.add(ENABLE_TOOLS);
        List<ToolSpecification> specs = toolRegistry.getSpecificationsByName(realMounts);
        logger.mountPhase(rec, 2, realMounts + " -> " + specs.size() + " specs");
        return specs;
    }

    private void validateToolExistence(SessionState state, List<ToolExecutionRequest> requests) {
        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.contains(req.name())) {
                state.getFormatCorrections().add("工具 " + req.name() + " 不存在，请使用可用工具。");
            }
        }
    }

    private List<ToolSpecification> ensureFinishMounted(List<ToolSpecification> tools) {
        if (tools.stream().anyMatch(t -> FINISH.equals(t.name()))) return tools;
        List<ToolSpecification> augmented = new ArrayList<>(tools);
        augmented.addAll(toolRegistry.getSpecificationsByName(Set.of(FINISH)));
        log.info("[STOP] finish tool force-mounted ({} -> {} specs)", tools.size(), augmented.size());
        return augmented;
    }

    /** 回填 AI 消息和工具结果到 JSONL，清理临时状态。 */
    private void finalizeAndAppend(TurnRecord rec, SessionState state) {
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
        }
        logger.flushed(rec, toolResults != null ? toolResults.size() : 0);

        state.getPendingNudges().clear();
        state.getFormatCorrections().clear();
        state.setPendingToolCalls(null);
        state.setLastToolResults(null);
    }
}
