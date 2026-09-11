package cn.kong.eon.agent;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.agent.exec.ToolCallDispatcher;
import cn.kong.eon.agent.guard.ToolCircuitBreaker;
import cn.kong.eon.agent.exec.TurnMessageWriter;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookDispatcher;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.stop.StopCategory;
import cn.kong.eon.agent.stop.StopHandler;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.*;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.session.SessionState;
import cn.kong.eon.tool.model.ToolCallRecord;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 核心引擎。每轮执行：PreModel → 构建上下文 → 调用 LLM → PostModel →
 * 工具执行(PreTool→Execute→PostTool) → 回填消息。无工具调用时任务完成。
 * 事件驱动：引擎在关键阶段发出 AgentEvent，由 AgentEventListener 消费。
 */
public class EonAgent {
    private static final Logger log = LoggerFactory.getLogger(EonAgent.class);

    // ── 核心依赖
    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolService toolService;
    private final TranscriptLedger transcriptLedger;
    private final String basePrompt;
    private final ToolContext toolContext;
    private final TokenCountEstimator tokenCountEstimator;

    // ── 协作组件
    private final CopyOnWriteArrayList<AgentEventListener> listeners;
    private final ToolCallDispatcher toolDispatcher;
    private final StopHandler stopHandler;
    private final TurnMessageWriter messageWriter;
    private final ToolCircuitBreaker circuitBreaker;

    // ── Hook 列表（按阶段分组）
    private final List<Hook.PreModelHook> preModelHooks = new ArrayList<>();
    private final List<Hook.PostModelHook> postModelHooks = new ArrayList<>();
    private final List<Hook.PreToolHook> preToolHooks = new ArrayList<>();
    private final List<Hook.PostToolHook> postToolHooks = new ArrayList<>();
    private int totalHookCount = 0;

    private static final long TOOL_SCHEMA_TOKENS_ESTIMATE = 220;
    private long cachedToolSchemaTokens = -1;

    // ═══════════════════════════════════════════════════════════════════
    //  构造与注册
    // ═══════════════════════════════════════════════════════════════════

    public EonAgent(AgentConfig config,
                    LlmClient llmClient,
                    ToolService toolService,
                    TranscriptLedger transcriptLedger,
                    String basePrompt,
                    ToolContext toolContext,
                    ToolCircuitBreaker circuitBreaker,
                    List<AgentEventListener> listeners,
                    ObjectMapper objectMapper) {
        this.config = config;
        this.llmClient = llmClient;
        this.toolService = toolService;
        this.transcriptLedger = transcriptLedger;
        this.basePrompt = basePrompt;
        this.toolContext = toolContext;
        this.circuitBreaker = circuitBreaker;
        this.listeners = new CopyOnWriteArrayList<>(listeners != null ? listeners : List.of());
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
        this.toolDispatcher = new ToolCallDispatcher(
                toolService, toolContext, this::emit, circuitBreaker,
                config.getTools().getParallelism(), objectMapper);
        this.messageWriter = new TurnMessageWriter(transcriptLedger);
        this.stopHandler = new StopHandler(config, this::emit);
    }

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

    public int getHookCount() {
        return totalHookCount;
    }

    public void shutdown() {
        toolDispatcher.shutdown();
        log.info("EonAgent 资源已释放");
    }

    /** 动态注册事件监听器。 */
    public void addListener(AgentEventListener listener) {
        listeners.add(listener);
    }

    /** 动态移除事件监听器。 */
    public void removeListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  事件分发
    // ═══════════════════════════════════════════════════════════════════

    private void emit(AgentEvent event) {
        for (AgentEventListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (Exception e) {
                log.warn("事件监听器异常: {}", e.getMessage(), e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════════

    public String run(SessionState state) {
        initRun(state);

        // 发出 session.status: running
        emit(SessionStatus.running());

        while (true) {
            // 中断检查
            if (state.isInterrupted()) {
                return completeExit(state, stopHandler.forceTerminate(
                        state, StopCategory.USER_INTERRUPTED, "用户主动中断"));
            }

            // 步数检查
            if (state.getTurnCount() >= config.getLoop().getMaxSteps()) {
                return completeExit(state, stopHandler.forceTerminate(
                        state, StopCategory.MAX_STEPS_REACHED,
                        StopCategory.MAX_STEPS_REACHED.format(config.getLoop().getMaxSteps())));
            }

            state.incrementTurn();

            try {
                LoopAction action = executeTurn(state);
                if (action.isExit()) {
                    return completeExit(state, ((LoopAction.Exit) action).output());
                }
            } catch (Exception e) {
                return completeExit(state, stopHandler.forceTerminate(
                        state, StopCategory.UNEXPECTED_ERROR,
                        StopCategory.UNEXPECTED_ERROR.format(e.getMessage())));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Turn 执行
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction executeTurn(SessionState state) {
        try {
            // ── 阶段 1：准备上下文 ──
            ContextBuilder ctx = buildContext(state);
            LoopAction preModel = prepareContext(state, ctx);
            if (preModel instanceof LoopAction.Exit exit) {
                return exit;
            }

            // ── 阶段 2：构建 messages ──
            List<ChatMessage> messages = ctx.build();
            state.setCurrentMessages(messages);

            // ── 阶段 3：调用 LLM（流式或同步） ──
            LlmResponse response;
            if (llmClient.isStreamEnabled()) {
                response = llmClient.streamChat(messages, toolService.getSpecifications(),
                        delta -> emit(AgentDelta.text(state.getTurnId(), delta)),
                        delta -> emit(AgentDelta.thinking(state.getTurnId(), delta)),
                        thinking -> emit(AgentThinking.now(state.getTurnId(), thinking)));
            } else {
                response = llmClient.chat(messages, toolService.getSpecifications());
            }
            state.setLastResponse(response);
            state.getUsageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            state.setLastAssistantText(thought);
            state.setLastThinking(response.aiMessage().thinking());
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();

            // ── 阶段 4：PostModel Hooks ──
            state.setPendingToolCalls(requests);
            LoopAction postModel = firePostModelHooks(state);
            if (postModel instanceof LoopAction.Exit exit) {
                return exit;
            }
            if (postModel instanceof LoopAction.Skip) {
                return finishSkip(state);
            }

            // ── 阶段 5：无工具调用 → 发出 agent.message 并退出 ──
            if (requests == null || requests.isEmpty()) {
                emit(AgentMessage.now(state.getTurnId(), state.getMessageId(), thought));
                return handleNoToolCalls(state, thought);
            }

            // ── 阶段 6：Extension Loop ──
            LoopAction extension = executeExtensionLoop(state, requests);
            if (extension instanceof LoopAction.Exit exit) {
                return exit;
            }

            // ── 阶段 7：推进熔断冷却 ──
            circuitBreaker.tickCooldown();

            return new LoopAction.Continue();
        } finally {
            // ── 阶段 8：消息回填 ──
            messageWriter.flush(state);
        }
    }

    private LoopAction executeExtensionLoop(SessionState state, List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        LoopAction preTool = firePreToolHooks(state, requests);
        if (preTool instanceof LoopAction.Exit exit) {
            return exit;
        }

        // 执行工具（ToolCallDispatcher 内部发出 agent.tool_use 和 agent.tool_result）
        List<ToolCallRecord> results = toolDispatcher.execute(state);

        // PostTool Hooks
        for (int i = 0; i < requests.size(); i++) {
            ToolCallRecord result = results.get(i);
            LoopAction postTool = firePostToolHooks(state, requests.get(i).name(), result.success());
            if (postTool instanceof LoopAction.Exit exit) {
                return exit;
            }
        }

        return new LoopAction.Continue();
    }

    private LoopAction handleNoToolCalls(SessionState state, String thought) {
        return new LoopAction.Exit(thought);
    }

    private LoopAction finishSkip(SessionState state) {
        circuitBreaker.tickCooldown();
        return new LoopAction.Continue();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  退出处理
    // ═══════════════════════════════════════════════════════════════════

    private String completeExit(SessionState state, String rawOutput) {
        saveSnapshot(state);
        String output = renderMemoryReferences(rawOutput);

        // 发出 session.usage
        emit(SessionUsage.now(state.getTurnId(), state.getMessageId(),
                state.getUsageAccum().getPromptTokens(),
                state.getUsageAccum().getCompletionTokens(),
                state.getUsageAccum().getTotalTokens()));

        // 发出 session.status: idle
        emit(SessionStatus.idle("task_completed"));

        return output;
    }

    private void saveSnapshot(SessionState state) {
        try {
            toolContext.snapshotStore().save(
                    toolContext.todoStore().getAll(),
                    state.getUsageAccum(),
                    state.getCompressionState());
            log.info("[Snapshot] 任务结束快照已保存: replayFrom={}, tokens={}",
                    state.getCompressionState().getReplayFromSeq(),
                    state.getUsageAccum().getTotalTokens());
        } catch (Exception e) {
            log.warn("[Snapshot] 任务结束快照保存失败: {}", e.getMessage());
        }
    }

    private String renderMemoryReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        return toolContext.memoryStore().renderReferences(text);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════════════

    private void initRun(SessionState state) {
        transcriptLedger.append(UserMessage.from(state.getUserInput()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文构建
    // ═══════════════════════════════════════════════════════════════════

    private ContextBuilder buildContext(SessionState state) {
        ContextBuilder ctx = new ContextBuilder();
        ctx.setTokenCountEstimator(tokenCountEstimator);
        ctx.setSystemPrompt(basePrompt);
        ctx.setSummary(state.getCompressionState().getLastSummary());
        ctx.setMemories(toolContext.memoryStore().renderForInjection());
        ctx.setWindow(transcriptLedger.window());

        ctx.setToolSchemaTokens(estimateToolSchemaTokens());
        ctx.setOutputReserveTokens(config.getLlm().getMaxTokens());
        ctx.setContextMaxTokens(config.getContext().getMaxTokens());
        return ctx;
    }

    private void consumeNudges(SessionState state, ContextBuilder ctx) {
        if (state.getNudges().isEmpty()) {
            return;
        }
        ctx.setNudges(String.join("\n", state.getNudges()));
        state.getNudges().clear();
    }

    private long estimateToolSchemaTokens() {
        if (cachedToolSchemaTokens < 0) {
            int specCount = toolService.getSpecifications().size();
            cachedToolSchemaTokens = specCount * TOOL_SCHEMA_TOKENS_ESTIMATE;
        }
        return cachedToolSchemaTokens;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction prepareContext(SessionState state, ContextBuilder ctx) {
        HookResult result = HookDispatcher.dispatchPreModel(preModelHooks, state, ctx);
        if (result != null && result.isStop()) {
            return exitOf(state, result);
        }
        consumeNudges(state, ctx);
        return new LoopAction.Continue();
    }

    private LoopAction firePostModelHooks(SessionState state) {
        HookResult result = HookDispatcher.dispatchPostModel(postModelHooks, state);
        if (result == null) {
            return new LoopAction.Continue();
        }
        if (result.isSkip()) {
            // 返回 Skip 让主循环跳过本轮后续阶段（阶段 5~7），由 finishSkip 收尾
            return new LoopAction.Skip();
        }
        if (result.isStop()) {
            return exitOf(state, result);
        }
        return new LoopAction.Continue();
    }

    private LoopAction firePreToolHooks(SessionState state, List<ToolExecutionRequest> requests) {
        HookResult result = HookDispatcher.dispatchPreTool(preToolHooks, state, requests);
        if (result != null && result.isStop()) {
            return exitOf(state, result);
        }
        return new LoopAction.Continue();
    }

    private LoopAction firePostToolHooks(SessionState state, String toolName, boolean success) {
        HookResult result = HookDispatcher.dispatchPostTool(postToolHooks, state, toolName, success);
        if (result != null && result.isStop()) {
            return exitOf(state, result);
        }
        return new LoopAction.Continue();
    }

    /** 把 Hook 的 stop 结果翻译成循环退出动作：终止文本由 StopHandler 生成。 */
    private LoopAction exitOf(SessionState state, HookResult result) {
        return new LoopAction.Exit(
                stopHandler.forceTerminate(state, result.getCategory(), result.getMessage()));
    }
}
