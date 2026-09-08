package cn.kong.eon.agent;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.stop.StopCategory;
import cn.kong.eon.agent.exec.ToolBreaker;
import cn.kong.eon.agent.turn.TurnOutcome;
import cn.kong.eon.agent.hook.HookDispatcher;
import cn.kong.eon.agent.flush.MessageFlusher;
import cn.kong.eon.agent.stop.StopHandler;
import cn.kong.eon.agent.exec.ToolExecHandler;
import cn.kong.eon.agent.turn.TurnLogger;
import cn.kong.eon.agent.turn.TurnRecord;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolExecutionResult;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心引擎。每轮执行：PreModel → 构建上下文 → 调用 LLM → PostModel →
 * 工具执行(PreTool→Execute→PostTool) → 回填消息。无工具调用时任务完成。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    // ── 核心依赖
    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final JsonlStore jsonlStore;
    private final String basePrompt;
    private final ToolContext toolContext;
    private final TokenCountEstimator tokenCountEstimator;

    // ── 协作组件
    private final TurnLogger logger;
    private final ToolExecHandler toolHandler;
    private final StopHandler stopHandler;
    private final MessageFlusher flusher;
    private final ToolBreaker breaker;

    // ── Hook 列表（按阶段分组）
    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>();
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();
    private int totalHookCount = 0;

    /**
     * 单个工具 schema token 估算均值
     */
    private static final long TOOL_SCHEMA_TOKENS_ESTIMATE = 220;

    /**
     * 缓存的工具 schema token 开销
     */
    private long cachedToolSchemaTokens = -1;

    // ═══════════════════════════════════════════════════════════════════
    //  构造与注册
    // ═══════════════════════════════════════════════════════════════════

    public EonAgent(AgentConfig config,
                    LlmClient llmClient,
                    ToolRegistry toolRegistry,
                    JsonlStore jsonlStore,
                    String basePrompt,
                    ToolContext toolContext,
                    ToolBreaker breaker) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.jsonlStore = jsonlStore;
        this.basePrompt = basePrompt;
        this.toolContext = toolContext;
        this.breaker = breaker;
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
        this.logger = new TurnLogger(config);
        this.toolHandler = new ToolExecHandler(
                toolRegistry, toolContext, logger, breaker,
                config.getTools().getParallelism());
        this.flusher = new MessageFlusher(jsonlStore);
        this.stopHandler = new StopHandler(config, logger);
    }

    /**
     * 注册 Hook，自动按 order 排序。
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
        }
        totalHookCount++;
        log.debug("Hook 已注册: {}", hook.name());
    }

    /**
     * 已注册 Hook 总数。
     */
    public int getHookCount() {
        return totalHookCount;
    }

    /**
     * 关闭 Agent。
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
     * 运行主循环，返回最终输出文本。
     */
    public String run(SessionState state) {
        initRun(state);

        while (true) {
            // 步数检查：达到上限终止
            if (state.getTurnCount() >= config.getLoop().getMaxSteps()) {
                return completeExit(state, stopHandler.forceTerminate(state, StopCategory.MAX_STEPS_REACHED, StopCategory.MAX_STEPS_REACHED.format(config.getLoop().getMaxSteps())));
            }

            state.incrementTurn();

            try {
                TurnOutcome action = executeTurn(state);
                if (action.isExit()) {
                    return completeExit(state, ((TurnOutcome.Exit) action).output());
                }
            } catch (Exception e) {
                return completeExit(state, stopHandler.forceTerminate(state, StopCategory.UNEXPECTED_ERROR, StopCategory.UNEXPECTED_ERROR.format(e.getMessage())));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Turn 执行
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 执行单个 Turn。返回 Continue 继续循环，Exit 退出并携带输出。
     */
    private TurnOutcome executeTurn(SessionState state) {
        TurnRecord rec = logger.newRecord();
        try {
            logger.turnHeader(rec, state);

            // ── 阶段 1：准备上下文（跑 PreModel Hooks，再渲染 nudge） ──
            ContextBuilder ctx = buildContext(state);
            TurnOutcome preModel = prepareContext(state, ctx);
            if (preModel instanceof TurnOutcome.Exit exit) {
                return exit;
            }

            // ── 阶段 2：构建 messages ──
            List<ChatMessage> messages = ctx.build();
            state.setCurrentMessages(messages);
            ContextMetrics metrics = ctx.metrics();
            logger.contextInfo(rec, metrics, messages.size(), toolRegistry.getAllToolNames().size());

            // ── 阶段 3：调用 LLM ──
            LlmResponse response = llmClient.chat(messages, toolRegistry.getSpecifications());
            state.setLastResponse(response);
            state.getUsageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            state.setLastAssistantText(thought);
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
            logger.llmResponse(rec, requests);

            // ── 阶段 4：PostModel Hooks（截断检测、工具校验、循环检测等） ──
            state.setPendingToolCalls(requests);
            TurnOutcome postModel = firePostModelHooks(state);
            if (postModel instanceof TurnOutcome.Exit exit) {
                return exit;
            }

            // ── 阶段 5：无工具调用 → 截断则继续循环，否则任务完成 ──
            if (requests == null || requests.isEmpty()) {
                return handleNoToolCalls(rec, state, thought);
            }

            // ── 阶段 6：Extension Loop（PreTool → 执行 → PostTool） ──
            TurnOutcome extension = executeExtensionLoop(rec, state, requests);
            if (extension instanceof TurnOutcome.Exit exit) {
                return exit;
            }

            // ── 阶段 7：回填 AI 消息和工具结果到 JSONL ──
            flusher.flushAndAppend(state);
            logger.turnDone(rec, state);

            // ── 阶段 8：推进熔断冷却 ──
            breaker.tickCooldown();

            return new TurnOutcome.Continue();
        } finally {
            // 兜底：确保任何退出路径都不会丢失未回填的消息
            flusher.flushIfPending(state);
            flushTurn(rec);
        }
    }

    /**
     * Extension Loop：PreTool → 执行 → PostTool。
     */
    private TurnOutcome executeExtensionLoop(TurnRecord rec, SessionState state,
                                             List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        TurnOutcome preTool = firePreToolHooks(state, requests);
        if (preTool instanceof TurnOutcome.Exit exit) {
            return exit;
        }

        // 执行工具
        List<ToolExecutionResult> results = toolHandler.execute(rec, state);

        // PostTool Hooks（逐个工具检查，遇到 Exit 停止）
        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionResult result = results.get(i);
            TurnOutcome postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof TurnOutcome.Exit exit) {
                return exit;
            }
        }

        return new TurnOutcome.Continue();
    }

    /**
     * 处理无工具调用：finishReason=length 时截断 nudge 已由 TruncationHook 注入，继续循环；
     * 否则任务完成退出。
     */
    private TurnOutcome handleNoToolCalls(TurnRecord rec, SessionState state, String thought) {
        flusher.flushAndAppend(state);
        boolean truncated = "length".equalsIgnoreCase(state.getLastResponse().finishReason());
        if (truncated) {
            logger.outputTruncated(rec);
            return new TurnOutcome.Continue();
        }
        return new TurnOutcome.Exit(thought);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  退出处理
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 退出处理：渲染记忆引用 → 记录日志 → 返回输出。
     */
    private String completeExit(SessionState state, String rawOutput) {
        String output = renderMemoryReferences(rawOutput);
        logger.agentComplete(state);
        return output;
    }

    /**
     * 将 [[memory:xxx]] 引用替换为记忆标题。
     */
    private String renderMemoryReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        return toolContext.memoryStore().renderReferences(text);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 初始化运行：记录日志、写入用户输入到 JSONL。
     */
    private void initRun(SessionState state) {
        logger.agentStart(state);
        // 写入用户输入到 JSONL
        jsonlStore.append(UserMessage.from(state.getUserInput()));
    }

    /**
     * 输出 Turn 日志。
     */
    private void flushTurn(TurnRecord rec) {
        logger.flush(rec);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文构建
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 构建 ContextBuilder。
     */
    private ContextBuilder buildContext(SessionState state) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setTokenCountEstimator(tokenCountEstimator);
        ctx.setSystemPrompt(basePrompt);
        ctx.setSummary(state.getCompressionState().getLastSummary());
        ctx.setMemories(toolContext.memoryStore().renderForInjection());
        ctx.setWindow(jsonlStore.window());

        ctx.setToolSchemaTokens(estimateToolSchemaTokens());
        ctx.setOutputReserveTokens(config.getLlm().getMaxTokens());
        ctx.setContextMaxTokens(config.getContext().getMaxTokens());
        return ctx;
    }

    /**
     * 渲染 nudge 进上下文并清空。渲染即消费，每条只进一次上下文。
     */
    private void consumeNudges(SessionState state, ContextBuilder ctx) {
        if (state.getNudges().isEmpty()) {
            return;
        }
        ctx.setNudges(String.join("\n", state.getNudges()));
        state.getNudges().clear();
    }

    /**
     * 估算工具 schema 的 token 开销，缓存后复用。
     */
    private long estimateToolSchemaTokens() {
        if (cachedToolSchemaTokens < 0) {
            int specCount = toolRegistry.getSpecifications().size();
            cachedToolSchemaTokens = specCount * TOOL_SCHEMA_TOKENS_ESTIMATE;
        }
        return cachedToolSchemaTokens;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 准备上下文：先跑 PreModel Hook，再渲染 nudge。顺序不可调换。
     */
    private TurnOutcome prepareContext(SessionState state, ContextBuilder ctx) {
        TurnOutcome outcome = HookDispatcher.dispatchPreModel(preModelHooks, state, ctx, stopHandler::forceTerminate);
        if (outcome instanceof TurnOutcome.Exit) {
            return outcome;
        }
        consumeNudges(state, ctx);
        return outcome;
    }

    private TurnOutcome firePostModelHooks(SessionState state) {
        return HookDispatcher.dispatchPostModel(postModelHooks, state, stopHandler::forceTerminate);
    }

    private TurnOutcome firePreToolHooks(SessionState state, List<ToolExecutionRequest> requests) {
        return HookDispatcher.dispatchPreTool(preToolHooks, state, requests, stopHandler::forceTerminate);
    }

    private TurnOutcome firePostToolHooks(SessionState state, String toolName, boolean success) {
        return HookDispatcher.dispatchPostTool(postToolHooks, state, toolName, success, stopHandler::forceTerminate);
    }
}
