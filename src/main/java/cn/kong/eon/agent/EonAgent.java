package cn.kong.eon.agent;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.agent.support.HookDispatcher;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.agent.support.MessageFinalizer;
import cn.kong.eon.agent.support.StopStateMachine;
import cn.kong.eon.agent.support.ToolExecutionHandler;
import cn.kong.eon.agent.support.TurnAction;
import cn.kong.eon.agent.support.TurnLogger;
import cn.kong.eon.agent.support.TurnRecord;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 统一引擎。
 * <p>
 * 核心循环流程（每轮 Turn）：
 * <pre>
 *   1. PreModel Hooks  → 预算检查、上下文压缩等
 *   2. 构建 messages   → System + Summary + Transcript + Memories + Nudges
 *   3. 调用 LLM        → 获取思考文本和工具调用请求
 *   4. PostModel Hooks → 循环检测等
 *   5. Extension Loop  → PreTool → 执行工具 → PostTool
 *   6. 回填消息        → AI 消息 + 工具结果写入 JSONL
 * </pre>
 * 无工具调用时任务完成；stop 请求时进入 grace period 优雅收尾。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    // ── 核心依赖 ──
    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final ToolContext toolContext;

    // ── 协作组件 ──
    private final TurnLogger logger;
    private final ToolExecutionHandler toolHandler;
    private final StopStateMachine stopStateMachine;
    private final MessageFinalizer finalizer;

    // ── Hook 列表（按阶段分组） ──
    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>();
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();
    private int totalHookCount = 0;

    /** 当前 Turn 的日志记录（供 Hook 调度器中 stopHandler 引用） */
    private TurnRecord currentRec;

    // ═══════════════════════════════════════════════════════════════════
    //  构造与注册
    // ═══════════════════════════════════════════════════════════════════

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
        this.toolHandler = new ToolExecutionHandler(
                toolRegistry, resultRenderer, toolContext, logger,
                loopDetector, config.getTools().getParallelism(), objectMapper);
        this.finalizer = new MessageFinalizer(jsonlStore);
        this.stopStateMachine = new StopStateMachine(config, logger, finalizer);
    }

    /**
     * 注册 Hook。一个 Hook 只属于一个阶段（PreModel/PostModel/PreTool/PostTool），
     * 注册后自动按 order 排序。
     */
    public void addHook(Hook hook) {
        if (hook instanceof Hook.PreModelHook h) {
            preModelHooks.add(h);
            preModelHooks.sort((a, b) -> Integer.compare(a.order(), b.order()));
        } else if (hook instanceof Hook.PostModelHook h) {
            postModelHooks.add(h);
            postModelHooks.sort((a, b) -> Integer.compare(a.order(), b.order()));
        } else if (hook instanceof Hook.PreToolHook h) {
            preToolHooks.add(h);
            preToolHooks.sort((a, b) -> Integer.compare(a.order(), b.order()));
        } else if (hook instanceof Hook.PostToolHook h) {
            postToolHooks.add(h);
            postToolHooks.sort((a, b) -> Integer.compare(a.order(), b.order()));
        } else {
            log.warn("未知的 Hook 类型，无法注册: {}", hook.getClass().getName());
            return;
        }
        totalHookCount++;
        log.debug("Hook 已注册: {}", hook.name());
    }

    public int getHookCount() {
        return totalHookCount;
    }

    /**
     * 关闭 Agent，释放线程池和工具资源。
     */
    public void shutdown() {
        toolHandler.shutdown();
        toolRegistry.closeAll();
        log.info("EonAgent 资源已释放");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 运行 Agent 主循环，返回最终输出文本。
     */
    public String run(SessionState state) {
        initRun(state);

        while (true) {
            // 步数检查：达到上限时触发优雅停止
            int maxSteps = state.isStopRequested()
                    ? config.getLoop().getAbsoluteMaxSteps()
                    : config.getLoop().getMaxSteps();
            if (state.getTurnCount() >= maxSteps) {
                TurnAction maxAction = stopStateMachine.handleMaxSteps(state);
                if (maxAction instanceof TurnAction.Exit exit) {
                    return completeExit(state, exit.output());
                }
                // 理论上不会走到这里，handleMaxSteps 总是返回 Exit
                return completeExit(state, "");
            }

            state.incrementTurn();
            int turnStartTokens = state.getUsageAccum().getTotalTokens();

            try {
                TurnAction action = executeTurn(state, turnStartTokens);
                if (action instanceof TurnAction.Exit exit) {
                    return completeExit(state, exit.output());
                }
            } catch (Exception e) {
                log.error("Agent 循环异常: {}", e.getMessage(), e);
                TurnAction action = stopStateMachine.handleLoopException(state, e);
                if (action instanceof TurnAction.Exit exit) {
                    return completeExit(state, exit.output());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Turn 执行
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 执行单个 Turn。try-finally 确保 finalize + flush 一定被执行。
     * <p>
     * 返回 {@link TurnAction}：Continue 继续循环，Exit 退出并携带输出。
     */
    private TurnAction executeTurn(SessionState state, int turnStartTokens) {
        TurnRecord rec = logger.newRecord();
        this.currentRec = rec;
        try {
            logger.turnHeader(rec, state);

            // ── 阶段 1：PreModel Hooks（预算检查、上下文压缩等） ──
            ContextBuilder ctx = buildContext(state);
            FireResult preModel = firePreModelHooks(state, ctx);
            if (preModel instanceof FireResult.Exit exit) {
                return new TurnAction.Exit(exit.output());
            }

            // Hooks 执行后重新渲染 nudge（BudgetHook 等可能在此阶段注入 nudge）
            renderNudges(state, ctx);

            // ── 阶段 2：构建 messages ──
            List<ChatMessage> messages = ctx.build();
            state.setCurrentMessages(messages);
            logger.contextInfo(rec, ctx, messages, state, toolRegistry.getAllToolNames().size());

            // ── 阶段 3：调用 LLM ──
            LlmResponse response = llmClient.chat(messages, toolRegistry.getSpecifications());
            state.setLastResponse(response);
            int deltaTokens = response.usage() != null ? response.usage().getTotalTokens() : 0;
            state.getUsageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            state.setLastAssistantText(thought);
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
            logger.llmResponse(rec, requests, deltaTokens);

            // ── 阶段 4：无工具调用 → 任务完成或截断处理 ──
            if (requests == null || requests.isEmpty()) {
                return handleNoToolCalls(rec, state, thought);
            }

            // ── 阶段 5：PostModel Hooks（循环检测等） ──
            validateToolExistence(state, requests);
            state.setPendingToolCalls(requests);
            FireResult postModel = firePostModelHooks(state, response);
            if (postModel instanceof FireResult.Exit exit) {
                return new TurnAction.Exit(exit.output());
            }
            if (postModel instanceof FireResult.Skip) {
                return new TurnAction.Continue();
            }

            // ── 阶段 6：Extension Loop（PreTool → 执行 → PostTool） ──
            FireResult extension = executeExtensionLoop(rec, state, requests);
            if (extension instanceof FireResult.Exit exit) {
                return new TurnAction.Exit(exit.output());
            }

            // ── 阶段 7：回填 AI 消息和工具结果到 JSONL ──
            finalizer.finalizeAndAppend(state);
            logger.turnDone(rec, state, turnStartTokens);

            // ── 阶段 8：stop 期间消耗 grace ──
            if (state.isStopRequested()) {
                return stopStateMachine.consumeGraceStep(rec, state, "stop 期间 LLM 仍在调用工具");
            }

            return new TurnAction.Continue();
        } finally {
            // 兜底：确保任何退出路径都不会丢失未回填的消息
            finalizer.finalizeIfPending(state);
            flushTurn(rec);
        }
    }

    /**
     * Extension Loop：PreTool → 执行工具 → PostTool。
     */
    private FireResult executeExtensionLoop(TurnRecord rec, SessionState state,
                                            List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        FireResult preTool = firePreToolHooks(state, requests);
        if (preTool instanceof FireResult.Exit exit) {
            return exit;
        }
        if (preTool instanceof FireResult.Skip) {
            return new FireResult.Continue();
        }

        // 执行工具
        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        // PostTool Hooks（逐个工具检查，遇到 Exit 或 Skip 停止）
        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            FireResult postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof FireResult.Exit exit) {
                return exit;
            }
            if (postTool instanceof FireResult.Skip) {
                break;
            }
        }

        return new FireResult.Continue();
    }

    /**
     * 处理无工具调用的情况：
     * <ul>
     *   <li>finishReason=length → 输出被截断，注入格式纠正提示，继续循环
     *   <li>stop 期间 → LLM 已输出总结，直接退出
     *   <li>正常 → 任务完成，退出
     * </ul>
     */
    private TurnAction handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        if ("length".equalsIgnoreCase(state.getLastResponse().finishReason())) {
            logger.outputTruncated(rec);
            state.addFormatCorrection(
                    "上一轮输出因长度限制被截断，工具调用未完成。请重新调用工具，如果内容过长请分多次写入。");
            finalizer.finalizeAndAppend(state);
            return new TurnAction.Continue();
        }

        finalizer.finalizeAndAppend(state);
        return new TurnAction.Exit(thought);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  退出处理
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 统一退出处理：渲染记忆引用 → 记录完成日志 → 返回输出。
     */
    private String completeExit(SessionState state, String rawOutput) {
        String output = renderMemoryReferences(rawOutput);
        logger.agentComplete(state);
        return output;
    }

    /**
     * 将 [[memory:xxx]] 引用替换为标题。
     */
    private String renderMemoryReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        return toolContext.memoryStore().renderReferences(text);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════════════

    private void initRun(SessionState state) {
        logger.agentStart(state);
        state.setStopState(SessionState.StopState.none());
        String tagged = "<user_query>\n" + state.getUserInput() + "\n</user_query>";
        jsonlStore.append(UserMessage.from(tagged));
    }

    private void flushTurn(TurnRecord rec) {
        logger.flush(rec);
        this.currentRec = null;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文构建
    // ═══════════════════════════════════════════════════════════════════

    private ContextBuilder buildContext(SessionState state) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setTokenCountEstimator(llmClient.getTokenCountEstimator());
        ctx.setSystemPrompt(basePrompt);
        if (state.getCompressionState().getLastSummary() != null) {
            ctx.setSummary(state.getCompressionState().getLastSummary());
        }
        ctx.setMemories(toolContext.memoryStore().renderForInjection());
        ctx.setTranscript(jsonlStore.snapshot());
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

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    private FireResult firePreModelHooks(SessionState state, ContextBuilder ctx) {
        return HookDispatcher.dispatchPreModel(
                preModelHooks, state, ctx,
                reason -> stopStateMachine.handleStop(currentRec, state, reason)
        );
    }

    private FireResult firePostModelHooks(SessionState state, LlmResponse response) {
        return HookDispatcher.dispatchPostModel(
                postModelHooks, state, response,
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> finalizer.finalizeIfPending(state)
        );
    }

    private FireResult firePreToolHooks(SessionState state, List<ToolExecutionRequest> requests) {
        return HookDispatcher.dispatchPreTool(
                preToolHooks, state, requests,
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> finalizer.finalizeIfPending(state)
        );
    }

    private FireResult firePostToolHooks(SessionState state, String toolName, boolean success) {
        return HookDispatcher.dispatchPostTool(
                postToolHooks, state, toolName, success,
                reason -> stopStateMachine.handleStop(currentRec, state, reason),
                () -> {}
        );
    }
}
