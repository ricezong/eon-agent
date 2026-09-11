package cn.kong.eon.engine;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookDispatcher;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.*;
import cn.kong.eon.llm.LlmService;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.runtime.SessionContext;
import cn.kong.eon.runtime.SessionState;
import cn.kong.eon.tool.model.ToolCallRecord;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolService;
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
 * Agent 核心引擎（无状态，可复用）。每次 {@code run(SessionContext)} 接收会话级依赖。
 * <p>
 * 每轮执行：PreModel → 构建上下文 → 调用 LLM → PostModel →
 * 工具执行(PreTool→Execute→PostTool) → 回填消息。无工具调用时任务完成。
 * 事件驱动：引擎在关键阶段发出 AgentEvent，由 AgentEventListener 消费。
 */
public class AgentEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    // ── 应用级依赖（构造一次，不随会话变化）
    private final AgentConfig config;
    private final LlmService llmService;
    private final ToolService toolService;
    private final String basePrompt;
    private final TokenCountEstimator tokenCountEstimator;

    private static final long TOOL_SCHEMA_TOKENS_ESTIMATE = 220;
    private long cachedToolSchemaTokens = -1;

    // ═══════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════

    public AgentEngine(AgentConfig config,
                       LlmService llmService,
                       ToolService toolService,
                       String basePrompt) {
        this.config = config;
        this.llmService = llmService;
        this.toolService = toolService;
        this.basePrompt = basePrompt;
        this.tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════════

    public String run(SessionContext ctx) {
        SessionState state = ctx.sessionState();
        HookBuckets hooks = groupHooks(ctx.hooks());

        initRun(ctx, state);

        // 发出 session.status: running
        emit(ctx, SessionStatus.running());

        while (true) {
            // 中断检查
            if (state.isInterrupted()) {
                return completeExit(ctx, state, ctx.stopHandler().forceTerminate(
                        state, StopCategory.USER_INTERRUPTED, "用户主动中断"));
            }

            // 步数检查
            if (state.getTurnCount() >= config.getLoop().getMaxSteps()) {
                return completeExit(ctx, state, ctx.stopHandler().forceTerminate(
                        state, StopCategory.MAX_STEPS_REACHED,
                        StopCategory.MAX_STEPS_REACHED.format(config.getLoop().getMaxSteps())));
            }

            state.incrementTurn();

            try {
                LoopAction action = executeTurn(ctx, state, hooks);
                if (action.isExit()) {
                    return completeExit(ctx, state, action.output());
                }
            } catch (Exception e) {
                return completeExit(ctx, state, ctx.stopHandler().forceTerminate(
                        state, StopCategory.UNEXPECTED_ERROR,
                        StopCategory.UNEXPECTED_ERROR.format(e.getMessage())));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Turn 执行
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction executeTurn(SessionContext ctx, SessionState state, HookBuckets hooks) {
        try {
            // ── 阶段 1：准备上下文 ──
            ContextBuilder contextBuilder = buildContext(ctx, state);
            LoopAction preModel = prepareContext(ctx, state, hooks, contextBuilder);
            if (preModel.isExit()) {
                return preModel;
            }

            // ── 阶段 2：构建 messages ──
            List<ChatMessage> messages = contextBuilder.build();
            state.setCurrentMessages(messages);

            // ── 阶段 3：调用 LLM（流式或同步） ──
            LlmResponse response;
            if (llmService.isStreamEnabled()) {
                response = llmService.streamChat(messages, toolService.getSpecifications(),
                        delta -> emit(ctx, AgentDelta.text(state.getTurnId(), delta)),
                        delta -> emit(ctx, AgentDelta.thinking(state.getTurnId(), delta)),
                        thinking -> emit(ctx, AgentThinking.now(state.getTurnId(), thinking)));
            } else {
                response = llmService.chat(messages, toolService.getSpecifications());
            }
            state.setLastResponse(response);
            state.getUsageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            state.setLastAssistantText(thought);
            state.setLastThinking(response.aiMessage().thinking());
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();

            // ── 阶段 4：PostModel Hooks ──
            state.setPendingToolCalls(requests);
            LoopAction postModel = firePostModelHooks(ctx, state, hooks);
            if (postModel.isExit()) {
                return postModel;
            }
            if (postModel.isSkip()) {
                return finishSkip(ctx, state);
            }

            // ── 阶段 5：无工具调用 → 发出 engine.message 并退出 ──
            if (requests == null || requests.isEmpty()) {
                emit(ctx, AgentMessage.now(state.getTurnId(), state.getMessageId(), thought));
                return handleNoToolCalls(state, thought);
            }

            // ── 阶段 6：Extension Loop ──
            LoopAction extension = executeExtensionLoop(ctx, state, hooks, requests);
            if (extension.isExit()) {
                return extension;
            }

            // ── 阶段 7：推进熔断冷却 ──
            ctx.circuitBreaker().tickCooldown();

            return LoopAction.CONTINUE;
        } finally {
            // ── 阶段 8：消息回填 ──
            ctx.messageWriter().flush(state);
        }
    }

    private LoopAction executeExtensionLoop(SessionContext ctx, SessionState state,
                                            HookBuckets hooks, List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        LoopAction preTool = firePreToolHooks(ctx, state, hooks, requests);
        if (preTool.isExit()) {
            return preTool;
        }

        // 执行工具（ToolCallDispatcher 内部发出 engine.tool_use 和 engine.tool_result）
        List<ToolCallRecord> results = ctx.toolDispatcher().execute(state);

        // PostTool Hooks
        for (int i = 0; i < requests.size(); i++) {
            ToolCallRecord result = results.get(i);
            LoopAction postTool = firePostToolHooks(ctx, state, hooks, requests.get(i).name(), result.success());
            if (postTool.isExit()) {
                return postTool;
            }
        }

        return LoopAction.CONTINUE;
    }

    private LoopAction handleNoToolCalls(SessionState state, String thought) {
        return LoopAction.exit(thought);
    }

    private LoopAction finishSkip(SessionContext ctx, SessionState state) {
        ctx.circuitBreaker().tickCooldown();
        return LoopAction.CONTINUE;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  退出处理
    // ═══════════════════════════════════════════════════════════════════

    private String completeExit(SessionContext ctx, SessionState state, String rawOutput) {
        saveSnapshot(ctx, state);
        String output = renderMemoryReferences(ctx, rawOutput);

        // 发出 session.usage
        emit(ctx, SessionUsage.now(state.getTurnId(), state.getMessageId(),
                state.getUsageAccum().getPromptTokens(),
                state.getUsageAccum().getCompletionTokens(),
                state.getUsageAccum().getTotalTokens()));

        // 发出 session.status: idle
        emit(ctx, SessionStatus.idle("task_completed"));

        return output;
    }

    private void saveSnapshot(SessionContext ctx, SessionState state) {
        try {
            ToolContext toolContext = ctx.toolContext();
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

    private String renderMemoryReferences(SessionContext ctx, String text) {
        if (text == null || text.isEmpty()) return text;
        return ctx.toolContext().memoryStore().renderReferences(text);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════════════

    private void initRun(SessionContext ctx, SessionState state) {
        ctx.transcriptLedger().append(UserMessage.from(state.getUserInput()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文构建
    // ═══════════════════════════════════════════════════════════════════

    private ContextBuilder buildContext(SessionContext ctx, SessionState state) {
        ContextBuilder contextBuilder = new ContextBuilder();
        contextBuilder.setTokenCountEstimator(tokenCountEstimator);
        contextBuilder.setSystemPrompt(basePrompt);
        contextBuilder.setSummary(state.getCompressionState().getLastSummary());
        contextBuilder.setMemories(ctx.toolContext().memoryStore().renderForInjection());
        contextBuilder.setWindow(ctx.transcriptLedger().window());

        contextBuilder.setToolSchemaTokens(estimateToolSchemaTokens());
        contextBuilder.setOutputReserveTokens(config.getLlm().getMaxTokens());
        contextBuilder.setContextMaxTokens(config.getContext().getMaxTokens());
        return contextBuilder;
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
    //  事件分发
    // ═══════════════════════════════════════════════════════════════════

    private static void emit(SessionContext ctx, AgentEvent event) {
        for (AgentEventListener l : ctx.listeners()) {
            try {
                l.onEvent(event);
            } catch (Exception e) {
                log.warn("事件监听器异常: {}", e.getMessage(), e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction prepareContext(SessionContext ctx, SessionState state,
                                      HookBuckets hooks, ContextBuilder contextBuilder) {
        HookResult result = HookDispatcher.dispatchPreModel(hooks.preModel, state, contextBuilder);
        if (result != null && result.isStop()) {
            return exitOf(ctx, state, result);
        }
        consumeNudges(state, contextBuilder);
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostModelHooks(SessionContext ctx, SessionState state, HookBuckets hooks) {
        HookResult result = HookDispatcher.dispatchPostModel(hooks.postModel, state);
        if (result == null) {
            return LoopAction.CONTINUE;
        }
        if (result.isSkip()) {
            return LoopAction.SKIP;
        }
        if (result.isStop()) {
            return exitOf(ctx, state, result);
        }
        return LoopAction.CONTINUE;
    }

    private LoopAction firePreToolHooks(SessionContext ctx, SessionState state,
                                        HookBuckets hooks, List<ToolExecutionRequest> requests) {
        HookResult result = HookDispatcher.dispatchPreTool(hooks.preTool, state, requests);
        if (result != null && result.isStop()) {
            return exitOf(ctx, state, result);
        }
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostToolHooks(SessionContext ctx, SessionState state,
                                         HookBuckets hooks, String toolName, boolean success) {
        HookResult result = HookDispatcher.dispatchPostTool(hooks.postTool, state, toolName, success);
        if (result != null && result.isStop()) {
            return exitOf(ctx, state, result);
        }
        return LoopAction.CONTINUE;
    }

    /** 把 Hook 的 stop 结果翻译成循环退出动作：终止文本由 StopHandler 生成。 */
    private LoopAction exitOf(SessionContext ctx, SessionState state, HookResult result) {
        return LoopAction.exit(
                ctx.stopHandler().forceTerminate(state, result.getCategory(), result.getMessage()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 分组
    // ═══════════════════════════════════════════════════════════════════

    /** 将平铺的 Hook 列表按阶段分组并排序。 */
    private static HookBuckets groupHooks(List<Hook> hooks) {
        List<Hook.PreModelHook> preModel = new ArrayList<>();
        List<Hook.PostModelHook> postModel = new ArrayList<>();
        List<Hook.PreToolHook> preTool = new ArrayList<>();
        List<Hook.PostToolHook> postTool = new ArrayList<>();

        for (Hook hook : hooks) {
            if (hook instanceof Hook.PreModelHook h) {
                preModel.add(h);
            } else if (hook instanceof Hook.PostModelHook h) {
                postModel.add(h);
            } else if (hook instanceof Hook.PreToolHook h) {
                preTool.add(h);
            } else if (hook instanceof Hook.PostToolHook h) {
                postTool.add(h);
            }
        }
        preModel.sort((a, b) -> Integer.compare(a.order(), b.order()));
        postModel.sort((a, b) -> Integer.compare(a.order(), b.order()));
        preTool.sort((a, b) -> Integer.compare(a.order(), b.order()));
        postTool.sort((a, b) -> Integer.compare(a.order(), b.order()));

        return new HookBuckets(preModel, postModel, preTool, postTool);
    }

    /** Hook 分组容器，仅在 run 期间存活。 */
    private record HookBuckets(
            List<Hook.PreModelHook> preModel,
            List<Hook.PostModelHook> postModel,
            List<Hook.PreToolHook> preTool,
            List<Hook.PostToolHook> postTool
    ) {
    }
}
