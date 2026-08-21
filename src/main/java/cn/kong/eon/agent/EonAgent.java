package cn.kong.eon.agent;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.agent.support.HookDispatcher;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.agent.support.ToolExecutionHandler;
import cn.kong.eon.agent.support.TurnLogger;
import cn.kong.eon.agent.support.TurnRecord;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.llm.LlmStalledException;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
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
 *   - 日志输出 → {@link TurnLogger}
 *   - 工具执行 → {@link ToolExecutionHandler}
 *   - Hook 调度 → {@link HookDispatcher}
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);


    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final ToolContext toolContext;

    private final TurnLogger logger;
    private final ToolExecutionHandler toolHandler;

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
                    ToolContext toolContext,
                    LoopDetector loopDetector) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.toolContext = toolContext;
        this.logger = new TurnLogger(config);
        this.toolHandler = new ToolExecutionHandler(toolRegistry, resultRenderer, toolContext, logger, loopDetector);
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
                TurnAction action = executeTurn(state, turnStartTokens);
                if (action instanceof TurnAction.Exit exit) return exit.output();
            } catch (Exception e) {
                TurnAction action = handleLoopException(state, e);
                if (action instanceof TurnAction.Exit exit) return exit.output();
            }
        }

        // maxSteps 在 while 循环外触发
        TurnAction action = handleMaxSteps(state);
        return action instanceof TurnAction.Exit exit ? exit.output() : "";
    }

    private void initRun(SessionState state) {
        logger.agentStart(state);
        state.setStopState(SessionState.StopState.none());
        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));
    }

    private boolean shouldContinue(SessionState state) {
        if (state.isFinished()) return false;
        int effectiveMax = state.isStopRequested()
                ? config.getLoop().absoluteMaxSteps
                : config.getLoop().maxSteps;
        return state.getTurnCount() < effectiveMax;
    }

    /**
     * 执行单个 Turn，返回 {@link TurnAction} 表达循环控制语义。
     * try-finally 确保 flushTurn 一定被执行，无需在每个返回点手动调用。
     */
    private TurnAction executeTurn(SessionState state, int turnStartTokens) {
        TurnRecord rec = logger.newRecord();
        this.currentRec = rec;
        try {
            logger.turnHeader(rec, state);

            // 1. 组装上下文 + PreModel Hooks
            ContextBuilder ctx = buildContext(state);
            FireResult preModel = firePreModelHooks(state, ctx);
            if (preModel instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());

            // 2. 构建 messages + 获取全部工具 Schema
            List<ChatMessage> messages = ctx.build();
            state.setCurrentMessages(messages);
            logger.contextInfo(rec, ctx, messages, state, toolRegistry.getAllToolNames().size());

            List<ToolSpecification> tools = toolRegistry.getSpecifications();

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
                return handleNoToolCalls(rec, state, thought);
            }

            // 5. PostModel Hooks (循环检测等)
            validateToolExistence(state, requests);
            state.setPendingToolCalls(requests);
            FireResult postModel = firePostModelHooks(state, response);
            if (postModel instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());
            if (postModel instanceof FireResult.Skip) return new TurnAction.Continue();

            // 6. Extension Loop: PreTool → Execute → PostTool
            FireResult extension = executeExtensionLoop(rec, state, requests);
            if (extension instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());

            // 7. 回填 + finish 检测
            finalizeAndAppend(rec, state);
            logger.turnDone(rec, state, turnStartTokens);

            if (state.isFinished()) {
                logger.agentFinish(state);
                return new TurnAction.Exit(state.getLastAssistantText());
            }

            // 8. stop 期间消耗 grace（非 finish 工具 / 无工具调用 两种场景统一处理）
            if (state.isStopRequested()) {
                return consumeGraceStep(rec, state, "LLM called non-finish tool");
            }

            return new TurnAction.Continue();
        } finally {
            flushTurn(rec);
        }
    }

    /** flush 当前 turn 日志并清理引用。 */
    private void flushTurn(TurnRecord rec) {
        logger.flush(rec);
        this.currentRec = null;
    }

    /** Extension Loop: PreTool → Execute → PostTool */
    private FireResult executeExtensionLoop(TurnRecord rec, SessionState state, List<ToolExecutionRequest> requests) {
        FireResult preTool = firePreToolHooks(state, requests);
        if (preTool instanceof FireResult.Exit) return preTool;
        if (preTool instanceof FireResult.Skip) return new FireResult.Continue();

        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            FireResult postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof FireResult.Exit) return postTool;
            if (postTool instanceof FireResult.Skip) break;
        }

        return new FireResult.Continue();
    }

    /** 处理无工具调用的情况。 */
    private TurnAction handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        // 截断检测
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            logger.outputTruncated(rec);
            state.addFormatCorrection(
                    "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。");
            finalizeAndAppend(rec, state);
            return new TurnAction.Continue();
        }

        // stop 期间 LLM 未调 finish：消耗 grace step
        if (state.isStopRequested()) {
            TurnAction action = consumeGraceStep(rec, state, "LLM did not call finish");
            if (action instanceof TurnAction.Exit exit) return exit;
            state.addFormatCorrection(
                    "请立即调用 finish 工具进行总结。这是最后的机会，否则任务将被强制终止。");
            finalizeAndAppend(rec, state);
            return new TurnAction.Continue();
        }

        // 正常聊天结束
        finalizeAndAppend(rec, state);
        return new TurnAction.Exit(thought);
    }

    // ===== 优雅停止子流程 =====

    /**
     * 消耗一个 grace step。返回 {@link TurnAction.Exit} 表示应退出循环（硬终止），
     * {@link TurnAction.Continue} 表示继续循环。
     * 统一处理两种场景：LLM 未调用任何工具 / LLM 调用了非 finish 工具。
     */
    private TurnAction consumeGraceStep(TurnRecord rec, SessionState state, String reason) {
        boolean hasMore = state.getStopState().consumeGraceStep();
        logger.graceConsumed(rec, reason, state.getStopState().getRemainingGraceSteps());
        if (!hasMore) {
            return new TurnAction.Exit(forceTerminate(state, state.getStopState().getReason()));
        }
        return new TurnAction.Continue();
    }

    private TurnAction handleLoopException(SessionState state, Exception e) {
        log.error("Agent loop unexpected error: {}", e.getMessage(), e);
        // executeTurn 的 finally 块已确保 flushTurn 被执行，此处无需再 flush
        // LLM 不可用时 grace period 无意义，直接硬终止
        if (e instanceof LlmStalledException) {
            return new TurnAction.Exit(forceTerminate(state, new StopReason(
                    StopCategory.UNEXPECTED_ERROR, "LLM 调用连续失败，模型不可用", 0)));
        }
        // 其他异常：尝试优雅停止
        FireResult sr = handleStop(null, state, new StopReason(
                StopCategory.UNEXPECTED_ERROR, e.getMessage(), config.getBudget().getGraceSteps()));
        return sr instanceof FireResult.Exit exit ? new TurnAction.Exit(exit.output()) : new TurnAction.Continue();
    }

    private TurnAction handleMaxSteps(SessionState state) {
        log.warn("[STOP] max steps reached: {}", config.getLoop().maxSteps);
        StopReason reason = new StopReason(
                StopCategory.MAX_STEPS_REACHED,
                "达到最大步数限制 (" + config.getLoop().maxSteps + ")",
                config.getBudget().getGraceSteps());
        // maxSteps 在 while 循环外触发，无 TurnRecord
        FireResult sr = handleStop(null, state, reason);
        return sr instanceof FireResult.Exit exit ? new TurnAction.Exit(exit.output())
                : new TurnAction.Exit(forceTerminate(state, reason));
    }

    /**
     * 处理 stop 请求：注入收尾 nudge，进入 grace period。
     * graceSteps=0 直接硬终止；已在 stop 中则追加 nudge 提醒，不重置 grace。
     *
     * @param rec 当前 TurnRecord，null 表示在 turn 之外（maxSteps/异常等场景）
     * @return {@link FireResult.Exit} 表示应退出循环；{@link FireResult.Continue} 表示继续循环
     */
    private FireResult handleStop(TurnRecord rec, SessionState state, StopReason reason) {
        if (reason.getGraceSteps() <= 0) {
            return new FireResult.Exit(forceTerminate(state, reason));
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
            return new FireResult.Continue();
        }

        // 已在 stop 中，追加提醒
        if (state.getStopState().getRemainingGraceSteps() <= 0) {
            return new FireResult.Exit(forceTerminate(state, reason));
        }
        state.addNudge(reason.toNudgeText());
        if (rec != null) {
            logger.stopEscalated(rec, reason.getCategory(), reason.getMessage());
        } else {
            log.warn("[STOP] escalated: {} | msg: {}", reason.getCategory(), reason.getMessage());
        }
        finalizeIfPending(rec, state);
        return new FireResult.Continue();
    }

    private String forceTerminate(SessionState state, StopReason reason) {
        logger.stopForced(reason.getCategory().name(), state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return formatTerminationOutput(state, reason);
    }

    /**
     * 拼接硬终止输出：终止原因 + 消耗统计。
     * Todo 进度、关键发现等由大模型在 grace period 自行总结，此处不重复拼接。
     */
    private String formatTerminationOutput(SessionState state, StopReason reason) {
        return "任务终止: " + reason.getCategory().getDisplayName() + "\n"
                + "原因: " + reason.getMessage() + "\n"
                + "消耗: " + state.getUsageAccum().getTotalTokens()
                + " tokens, " + state.getTurnCount() + " 轮\n";
    }

    private void finalizeIfPending(TurnRecord rec, SessionState state) {
        if (state.getPendingToolCalls() != null || state.getLastToolResults() != null) {
            finalizeAndAppend(rec, state);
        }
    }

    // ===== Hook 调度（统一委托 HookDispatcher）=====

    private FireResult firePreModelHooks(SessionState state, ContextBuilder ctx) {
        // PreModel 的 stop 语义：handleStop 返回 Continue 后继续遍历后续 hook
        // （BudgetHook stop 后，ContextCompactHook 仍需执行）
        return HookDispatcher.dispatchPreModel(
                preModelHooks, state,
                (hook, s) -> hook.beforeModelCall(s, ctx),
                reason -> handleStop(currentRec, state, reason)
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
        // 渲染运行时提醒到上下文
        renderNudges(state, ctx);
        return ctx;
    }

    /** 将 pendingNudges 和 formatCorrections 渲染到 ContextBuilder。 */
    private void renderNudges(SessionState state, ContextBuilder ctx) {
        if (state.getPendingNudges().isEmpty() && state.getFormatCorrections().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("## [Runtime] 运行时提醒（本轮有效）\n");
        for (String nudge : state.getPendingNudges()) {
            sb.append("- ").append(nudge).append("\n");
        }
        for (String correction : state.getFormatCorrections()) {
            sb.append("- ").append(correction).append("\n");
        }
        ctx.setRuntimeNudges(sb.toString());
    }

    private void validateToolExistence(SessionState state, List<ToolExecutionRequest> requests) {
        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.contains(req.name())) {
                state.getFormatCorrections().add("工具 " + req.name() + " 不存在，请使用可用工具。");
            }
        }
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
