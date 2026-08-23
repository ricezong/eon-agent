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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Agent 统一引擎。Core Loop 组装上下文并调用 LLM，Extension Loop 执行工具后回到 Core Loop。
 * 职责委托：日志→{@link TurnLogger}，工具执行→{@link ToolExecutionHandler}，Hook 调度→{@link HookDispatcher}。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);


    private final AgentConfig config;       // 配置
    private final LlmClient llmClient;      // LLM 客户端
    private final ToolRegistry toolRegistry; // 工具注册表
    private final JsonlStore jsonlStore;    // 消息存储
    private final String basePrompt;        // 系统提示词
    private final ToolContext toolContext;  // 工具执行上下文

    private final TurnLogger logger;        // 日志器
    private final ToolExecutionHandler toolHandler; // 工具执行处理器

    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();   // 模型调用前 Hook
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>(); // 模型调用后 Hook
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();     // 工具执行前 Hook
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();   // 工具执行后 Hook
    private int totalHookCount = 0;

    private TurnRecord currentRec;  // 当前 Turn 的日志收集器，null 表示在 turn 之外

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
        this.toolHandler = new ToolExecutionHandler(toolRegistry, resultRenderer, toolContext, logger, loopDetector, config.getTools().parallelism);
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

    /** 关闭 Agent 释放资源（工具线程池等）。 */
    public void shutdown() {
        if (toolHandler != null) {
            toolHandler.shutdown();
        }
        log.info("EonAgent resources released");
    }

    // ===== 主循环 =====

    /** 运行 Agent 主循环。 */
    public String run(SessionState state) {
        return runStream(state, null);
    }

    /**
     * 运行 Agent 主循环（带流式回调）。
     * <p>
     * 在关键节点调用 {@link TurnCallback}，用于 SSE 事件推送。
     * callback 为 null 时行为与 {@link #run} 完全一致。
     */
    public String runStream(SessionState state, TurnCallback callback) {
        initRun(state);

        if (callback != null) {
            safeCallback(() -> callback.onRunStart(state.getSessionId(), state.getUserOriginalInput()));
        }

        while (shouldContinue(state)) {
            state.incrementTurn();
            int turnStartTokens = state.getUsageAccum().getTotalTokens();

            if (callback != null) {
                final int turn = state.getTurnCount();
                safeCallback(() -> callback.onTurnStart(turn));
            }

            try {
                TurnAction action = executeTurn(state, turnStartTokens, callback);
                if (action instanceof TurnAction.Exit exit) {
                    String output = renderMemoryReferences(exit.output());
                    if (callback != null) {
                        final int tc = state.getTurnCount();
                        final int tokens = state.getUsageAccum().getTotalTokens();
                        safeCallback(() -> callback.onOutput(output, tc, tokens));
                    }
                    return output;
                }
                if (callback != null) {
                    safeCallback(() -> callback.onTurnEnd(
                            state.getTurnCount(), state.getUsageAccum().getTotalTokens()));
                }
            } catch (Exception e) {
                if (callback != null) {
                    safeCallback(() -> callback.onError(e.getMessage()));
                }
                TurnAction action = handleLoopException(state, e);
                if (action instanceof TurnAction.Exit exit) {
                    String output = renderMemoryReferences(exit.output());
                    if (callback != null) {
                        final int tc = state.getTurnCount();
                        final int tokens = state.getUsageAccum().getTotalTokens();
                        safeCallback(() -> callback.onOutput(output, tc, tokens));
                    }
                    return output;
                }
            }
        }

        // maxSteps 在 while 循环外触发
        TurnAction action = handleMaxSteps(state);
        String output = action instanceof TurnAction.Exit exit ? exit.output() : "";
        output = renderMemoryReferences(output);
        if (callback != null) {
            String reason = output;
            safeCallback(() -> callback.onTerminate(reason, state.getTurnCount(), state.getUsageAccum().getTotalTokens()));
        }
        return output;
    }

    /** 将 [[memory:xxx]] 引用替换为标题（内容摘要）。 */
    private String renderMemoryReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        return toolContext.memoryStore().renderReferences(text);
    }

    private void initRun(SessionState state) {
        logger.agentStart(state);
        state.setStopState(SessionState.StopState.none());
        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));
    }

    private boolean shouldContinue(SessionState state) {
        int effectiveMax = state.isStopRequested()
                ? config.getLoop().absoluteMaxSteps
                : config.getLoop().maxSteps;
        return state.getTurnCount() < effectiveMax;
    }

    /** 执行单个 Turn。try-finally 确保 flushTurn 一定被执行。 */
    private TurnAction executeTurn(SessionState state, int turnStartTokens, TurnCallback callback) {
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

            // SSE 回调：LLM 响应到达
            if (callback != null) {
                final List<String> toolNames = requests != null
                        ? requests.stream().map(ToolExecutionRequest::name).toList()
                        : List.of();
                safeCallback(() -> callback.onLlmResponse(thought, toolNames));
            }

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
            FireResult extension = executeExtensionLoop(rec, state, requests, callback);
            if (extension instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());

            // 7. 回填
            finalizeAndAppend(rec, state);
            logger.turnDone(rec, state, turnStartTokens);

            // 8. stop 期间消耗 grace（LLM 仍在调用工具时消耗 grace step）
            if (state.isStopRequested()) {
                return consumeGraceStep(rec, state, "LLM called tool during stop");
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

    /** Extension Loop: PreTool → Execute → PostTool。 */
    private FireResult executeExtensionLoop(TurnRecord rec, SessionState state,
                                           List<ToolExecutionRequest> requests, TurnCallback callback) {
        FireResult preTool = firePreToolHooks(state, requests);
        if (preTool instanceof FireResult.Exit) return preTool;
        if (preTool instanceof FireResult.Skip) return new FireResult.Continue();

        // SSE 回调：工具开始执行
        if (callback != null) {
            for (ToolExecutionRequest req : requests) {
                safeCallback(() -> callback.onToolStart(req.name(), req.id()));
            }
        }

        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        // SSE 回调：工具执行完成
        if (callback != null) {
            for (int i = 0; i < requests.size() && i < results.size(); i++) {
                ToolExecutionResult result = results.get(i);
                String summary = result.content();
                if (summary != null && summary.length() > 200) {
                    summary = summary.substring(0, 200) + "...(truncated)";
                }
                final String toolName = requests.get(i).name();
                final boolean success = result.success();
                final String toolSummary = summary;
                safeCallback(() -> callback.onToolResult(toolName, success, toolSummary));
            }
        }

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            FireResult postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof FireResult.Exit) return postTool;
            if (postTool instanceof FireResult.Skip) break;
        }

        return new FireResult.Continue();
    }

    /** 安全执行回调，吞掉异常以免中断 Agent 主循环。 */
    private void safeCallback(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("TurnCallback error: {}", e.getMessage(), e);
        }
    }

    /** 处理无工具调用的情况（方案语义：无工具调用 = 任务完成，直接退出）。 */
    private TurnAction handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        // 截断检测
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            logger.outputTruncated(rec);
            state.addFormatCorrection(
                    "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。");
            finalizeAndAppend(rec, state);
            return new TurnAction.Continue();
        }

        // 正常聊天结束（stop 期间 LLM 未调用工具 = 已输出总结，直接退出）
        finalizeAndAppend(rec, state);
        return new TurnAction.Exit(thought);
    }

    // ===== 优雅停止子流程 =====

    /** 消耗一个 grace step，返回 Exit 表示硬终止，Continue 表示继续循环。 */
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

    /** 拼接硬终止输出：终止原因 + 消耗统计。 */
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

    // ===== Hook 调度（委托 HookDispatcher）=====

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
        // 动态注入块（方案 §2.10）
        String userInfo = cn.kong.eon.context.dynamic.UserInfoProvider.generate(toolContext.workDir());
        ctx.setUserInfo(userInfo);
        String rules = loadUserRules();
        if (rules != null) ctx.setRules(rules);
        String mems = toolContext.memoryStore().renderForInjection();
        ctx.setMemories(mems);
        String skills = cn.kong.eon.context.dynamic.SkillsIndexProvider.generate(
                Path.of(config.getStorage().baseDir, "skills").toString());
        ctx.setAgentSkills(skills);
        ctx.setTranscript(jsonlStore.snapshot());
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

    /** 从外部文件加载用户规则。仅在用户显式创建时注入，classpath 模板不注入。 */
    private String loadUserRules() {
        // 只从外部文件加载，不从 classpath 加载模板
        Path externalPath = Path.of("prompts/user_rules.md");
        if (!Files.exists(externalPath)) {
            return null;
        }
        try {
            String content = Files.readString(externalPath).trim();
            if (content.isEmpty()) {
                return null;
            }
            // 过滤掉纯注释/标题模板：去掉注释和标题行后检查是否有实际内容
            String actualContent = content.lines()
                    .filter(line -> {
                        String trimmed = line.trim();
                        return !trimmed.isEmpty()
                                && !trimmed.startsWith("#")
                                && !trimmed.startsWith("<!--");
                    })
                    .reduce("", (a, b) -> a + "\n" + b).trim();
            if (actualContent.isEmpty()) {
                return null;
            }
            return "<rules>\n" + actualContent + "\n</rules>";
        } catch (java.io.IOException e) {
            log.debug("User rules not loaded: {}", e.getMessage());
        }
        return null;
    }

    /** 校验工具是否存在，不存在则注入格式纠正提示。 */
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
