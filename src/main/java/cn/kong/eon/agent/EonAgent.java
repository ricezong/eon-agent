package cn.kong.eon.agent;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.support.HookDispatcher;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.agent.support.MessageFinalizer;
import cn.kong.eon.agent.support.StopStateMachine;
import cn.kong.eon.agent.support.ToolExecutionHandler;
import cn.kong.eon.agent.support.TurnAction;
import cn.kong.eon.agent.support.TurnLogger;
import cn.kong.eon.agent.support.TurnRecord;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Agent 统一引擎。Core Loop 组装上下文并调用 LLM，Extension Loop 执行工具后回到 Core Loop。
 * <p>
 * 架构分层：
 * <pre>
 *   runStream ──→ while loop ──→ executeTurn ──→ { PreModel → LLM → PostModel → Extension Loop }
 *                                      ↑                              ↓
 *                                      └──────── TurnAction ─────────┘
 *
 *   executeTurn 内部阶段（序号即执行顺序）：
 *     1. PreModel Hooks   — 预算检查、上下文压缩
 *     2. 构建 messages     — 从 transcript + memories + nudges 组装
 *     3. 调用 LLM          — 获取 AI 响应和工具调用请求
 *     4. 无工具调用？      — 是→ handleNoToolCalls（截断/完成）
 *     5. PostModel Hooks   — 循环检测
 *     6. Extension Loop    — PreTool → Execute → PostTool
 *     7. 回填              — AI 消息和工具结果写入 JSONL
 *     8. Grace 消耗        — stop 期间 LLM 仍在调用工具时消耗 grace step
 * </pre>
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
    private final StopStateMachine stopStateMachine;
    private final MessageFinalizer finalizer;

    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>();
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();
    private int totalHookCount = 0;

    private TurnRecord currentRec;  // null 表示在 turn 之外

    public EonAgent(AgentConfig config,
                    LlmClient llmClient,
                    ToolRegistry toolRegistry,
                    ToolResultRenderer resultRenderer,
                    JsonlStore jsonlStore,
                    String basePrompt,
                    ToolContext toolContext,
                    LoopDetector loopDetector,
                    ObjectMapper objectMapper) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.toolContext = toolContext;
        this.logger = new TurnLogger(config);
        this.toolHandler = new ToolExecutionHandler(toolRegistry, resultRenderer, toolContext, logger, loopDetector, config.getTools().getParallelism(), objectMapper);
        this.finalizer = new MessageFinalizer(jsonlStore);
        this.stopStateMachine = new StopStateMachine(config, logger, finalizer);
    }


    public void addHook(Hook hook) {
        boolean added = false;
        added |= tryAddHook(hook, Hook.PreModelHook.class, preModelHooks);
        added |= tryAddHook(hook, Hook.PostModelHook.class, postModelHooks);
        added |= tryAddHook(hook, Hook.PreToolHook.class, preToolHooks);
        added |= tryAddHook(hook, Hook.PostToolHook.class, postToolHooks);
        if (added) {
            totalHookCount++;
            log.debug("Hook 已注册: {}", hook.name());
        }
    }

    @SuppressWarnings("unchecked")
    private <H extends Hook> boolean tryAddHook(Hook hook, Class<H> hookType, List<H> list) {
        if (!hookType.isInstance(hook)) return false;
        list.add((H) hook);
        list.sort(Comparator.comparingInt(Hook::order));
        return true;
    }

    public int getHookCount() {
        return totalHookCount;
    }

    /**
     * 关闭 Agent 释放资源（工具线程池、工具持有的资源等）。
     */
    public void shutdown() {
        if (toolHandler != null) {
            toolHandler.shutdown();
        }
        toolRegistry.closeAll();
        log.info("EonAgent 资源已释放");
    }


    /**
     * 运行 Agent 主循环。
     */
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

        while (true) {
            // 步数上限检查：正常用 maxSteps，stop 期间切换到 absoluteMaxSteps
            int effectiveMax = state.isStopRequested()
                    ? config.getLoop().getAbsoluteMaxSteps()
                    : config.getLoop().getMaxSteps();
            if (state.getTurnCount() >= effectiveMax) {
                // maxSteps 达到上限，尝试优雅停止
                TurnAction action = stopStateMachine.handleMaxSteps(state);
                String output = action instanceof TurnAction.Exit exit ? exit.output() : "";
                return completeExit(state, output, callback, true);
            }

            state.incrementTurn(); // 达到步数上限时切换 absoluteMaxSteps
            int turnStartTokens = state.getUsageAccum().getTotalTokens();

            if (callback != null) {
                final int turn = state.getTurnCount();
                safeCallback(() -> callback.onTurnStart(turn));
            }

            try {
                TurnAction action = executeTurn(state, turnStartTokens, callback);
                if (action instanceof TurnAction.Exit exit) {
                    return completeExit(state, exit.output(), callback, false);
                }
                if (callback != null) {
                    safeCallback(() -> callback.onTurnEnd(
                            state.getTurnCount(), state.getUsageAccum().getTotalTokens()));
                }
            } catch (Exception e) {
                if (callback != null) {
                    safeCallback(() -> callback.onError(e.getMessage()));
                }
                TurnAction action = stopStateMachine.handleLoopException(state, e);
                if (action instanceof TurnAction.Exit exit) {
                    return completeExit(state, exit.output(), callback, true);
                }
            }
        }
    }

    /**
     * 统一的退出处理：渲染记忆引用 → 记录完成日志 → 回调输出。
     *
     * @param forced true 表示被强制终止（maxSteps/异常），调用 onTerminate；false 表示正常完成，调用 onOutput
     */
    private String completeExit(SessionState state, String rawOutput, TurnCallback callback, boolean forced) {
        String output = renderMemoryReferences(rawOutput);
        logger.agentComplete(state);
        if (callback != null) {
            final int tc = state.getTurnCount();
            final int tokens = state.getUsageAccum().getTotalTokens();
            if (forced) {
                safeCallback(() -> callback.onTerminate(output, tc, tokens));
            } else {
                safeCallback(() -> callback.onOutput(output, tc, tokens));
            }
        }
        return output;
    }

    /**
     * 将 [[memory:xxx]] 引用替换为标题（内容摘要）。
     */
    private String renderMemoryReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        return toolContext.memoryStore().renderReferences(text);
    }

    private void initRun(SessionState state) {
        logger.agentStart(state);
        state.setStopState(SessionState.StopState.none());
        jsonlStore.append(UserMessage.from(state.getUserOriginalInput()));
    }


    /**
     * 执行单个 Turn。try-finally 确保 finalize + flushTurn 一定被执行。
     */
    private TurnAction executeTurn(SessionState state, int turnStartTokens, TurnCallback callback) {
        TurnRecord rec = logger.newRecord();
        this.currentRec = rec;
        try {
            logger.turnHeader(rec, state);

            // 1. PreModel Hooks（预算检查、上下文压缩等）
            ContextBuilder ctx = buildContext(state);
            FireResult preModel = firePreModelHooks(state, ctx);
            if (preModel instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());

            // 2. 构建 messages + 获取工具 Schema
            List<ChatMessage> messages = ctx.build();
            state.setCurrentMessages(messages);
            logger.contextInfo(rec, ctx, messages, state, toolRegistry.getAllToolNames().size());

            // 3. 调用 LLM
            LlmResponse response = llmClient.chat(messages, toolRegistry.getSpecifications());
            state.setLastResponse(response);
            int deltaTokens = response.usage() != null ? response.usage().getTotalTokens() : 0;
            state.getUsageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            state.setLastAssistantText(thought);
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
            logger.llmResponse(rec, requests, deltaTokens);

            if (callback != null) {
                notifyLlmResponse(callback, thought, requests);
            }

            // 4. 无工具调用 → 任务完成或截断处理
            if (requests == null || requests.isEmpty()) {
                return handleNoToolCalls(rec, state, thought);
            }

            // 5. PostModel Hooks（循环检测等）
            validateToolExistence(state, requests);
            state.setPendingToolCalls(requests);
            FireResult postModel = firePostModelHooks(state, response);
            if (postModel instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());
            if (postModel instanceof FireResult.Skip) return new TurnAction.Continue();

            // 6. Extension Loop: PreTool → Execute → PostTool
            FireResult extension = executeExtensionLoop(rec, state, requests, callback);
            if (extension instanceof FireResult.Exit exit) return new TurnAction.Exit(exit.output());

            // 7. 回填 AI 消息和工具结果到 JSONL
            finalizer.finalizeAndAppend(rec, state);
            logger.turnDone(rec, state, turnStartTokens);

            // 8. stop 期间消耗 grace（LLM 仍在调用工具时消耗 grace step）
            if (state.isStopRequested()) {
                return stopStateMachine.consumeGraceStep(rec, state, "stop 期间 LLM 仍在调用工具");
            }

            return new TurnAction.Continue();
        } finally {
            // 兜底：确保任何退出路径都不会丢失未回填的 AI 消息和工具结果
            finalizer.finalizeIfPending(rec, state);
            flushTurn(rec);
        }
    }

    /**
     * flush 当前 turn 日志并清理 currentRec 引用。
     */
    private void flushTurn(TurnRecord rec) {
        logger.flush(rec);
        this.currentRec = null;
    }


    /**
     * Extension Loop: PreTool → Execute → PostTool。
     */
    private FireResult executeExtensionLoop(TurnRecord rec, SessionState state,
                                            List<ToolExecutionRequest> requests, TurnCallback callback) {
        FireResult preTool = firePreToolHooks(state, requests);
        if (preTool instanceof FireResult.Exit) return preTool;
        if (preTool instanceof FireResult.Skip) return new FireResult.Continue();

        if (callback != null) {
            for (ToolExecutionRequest req : requests) {
                safeCallback(() -> callback.onToolStart(req.name(), req.id()));
            }
        }

        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        if (callback != null) {
            notifyToolResults(callback, requests, results);
        }

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            FireResult postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof FireResult.Exit) return postTool;
            if (postTool instanceof FireResult.Skip) break;
        }

        return new FireResult.Continue();
    }


    private void notifyLlmResponse(TurnCallback callback, String thought, List<ToolExecutionRequest> requests) {
        List<String> toolNames = requests != null
                ? requests.stream().map(ToolExecutionRequest::name).toList()
                : List.of();
        safeCallback(() -> callback.onLlmResponse(thought, toolNames));
    }

    private void notifyToolResults(TurnCallback callback,
                                   List<ToolExecutionRequest> requests,
                                   List<ToolExecutionResult> results) {
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

    /**
     * 安全执行回调，吞掉异常以免中断 Agent 主循环。
     */
    private void safeCallback(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("TurnCallback 回调异常: {}", e.getMessage(), e);
        }
    }


    /**
     * 处理无工具调用的情况（方案语义：无工具调用 = 任务完成，直接退出）。
     */
    private TurnAction handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        // 截断检测
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            logger.outputTruncated(rec);
            state.addFormatCorrection(
                    "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。");
            finalizer.finalizeAndAppend(rec, state);
            return new TurnAction.Continue();
        }

        // 正常聊天结束（stop 期间 LLM 未调用工具 = 已输出总结，直接退出）
        finalizer.finalizeAndAppend(rec, state);
        return new TurnAction.Exit(thought);
    }


    private FireResult firePreModelHooks(SessionState state, ContextBuilder ctx) {
        return HookDispatcher.dispatchPreModel(
                preModelHooks, state,
                (hook, s) -> hook.beforeModelCall(s, ctx),
                reason -> stopStateMachine.handleStop(currentRec, state, reason)
        );
    }

    private FireResult firePostModelHooks(SessionState state, LlmResponse response) {
        return HookDispatcher.dispatch(
                postModelHooks, state,
                (hook, s) -> hook.afterModelCall(s, response),
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> finalizer.finalizeIfPending(currentRec, state)
        );
    }

    private FireResult firePreToolHooks(SessionState state, List<ToolExecutionRequest> requests) {
        return HookDispatcher.dispatch(
                preToolHooks, state,
                (hook, s) -> hook.beforeToolExecution(s, requests),
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> finalizer.finalizeIfPending(currentRec, state)
        );
    }

    private FireResult firePostToolHooks(SessionState state, String toolName, boolean success) {
        return HookDispatcher.dispatch(
                postToolHooks, state,
                (hook, s) -> hook.afterToolExecution(s, toolName, success),
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> {
                }  // PostTool stop 不 finalize
        );
    }


    private ContextBuilder buildContext(SessionState state) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setTokenCountEstimator(llmClient.getTokenCountEstimator());
        ctx.setSystemPrompt(basePrompt);
        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }
        ctx.setMemories(toolContext.memoryStore().renderForInjection());
        ctx.setTranscript(jsonlStore.snapshot());
        renderNudges(state, ctx);
        return ctx;
    }

    /**
     * 将 pendingNudges 和 formatCorrections 渲染到 ContextBuilder。
     */
    private void renderNudges(SessionState state, ContextBuilder ctx) {
        if (state.getPendingNudges().isEmpty() && state.getFormatCorrections().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("<runtime_nudges>\n");
        for (String nudge : state.getPendingNudges()) {
            sb.append("- ").append(nudge).append("\n");
        }
        for (String correction : state.getFormatCorrections()) {
            sb.append("- ").append(correction).append("\n");
        }
        sb.append("</runtime_nudges>");
        ctx.setRuntimeNudges(sb.toString());
    }

    /**
     * 校验工具是否存在，不存在则注入格式纠正提示。
     */
    private void validateToolExistence(SessionState state, List<ToolExecutionRequest> requests) {
        for (ToolExecutionRequest req : requests) {
            if (!toolRegistry.contains(req.name())) {
                state.getFormatCorrections().add("工具 " + req.name() + " 不存在，请使用可用工具。");
            }
        }
    }
}
