package cn.kong.eon.engine;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.engine.exec.ToolCallDispatcher;
import cn.kong.eon.engine.exec.TurnMessageWriter;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookDispatcher;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.engine.stop.StopHandler;
import cn.kong.eon.event.*;
import cn.kong.eon.llm.LlmService;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.tool.ToolService;
import cn.kong.eon.tool.model.ToolCallRecord;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 核心引擎（无状态单例）。每轮执行：PreModel → LLM → PostModel →
 * 工具执行(PreTool→Execute→PostTool) → 回填消息。无工具调用时任务完成。
 */
@Component
public class AgentEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    // ── 应用级依赖（构造注入，不随会话变化）
    private final AgentConfig config;
    private final LlmService llmService;
    private final ToolService toolService;
    private final String basePrompt;
    private final TokenCountEstimator tokenCountEstimator;
    private final HookBuckets hooks;
    private final ToolCallDispatcher dispatcher;
    private final TurnMessageWriter messageWriter;
    private final StopHandler stopHandler;

    private static final long TOOL_SCHEMA_TOKENS_ESTIMATE = 220;

    public AgentEngine(AgentConfig config,
                       LlmService llmService,
                       ToolService toolService,
                       TokenCountEstimator tokenCountEstimator,
                       List<Hook> allHooks,
                       ToolCallDispatcher dispatcher,
                       TurnMessageWriter messageWriter,
                       StopHandler stopHandler,
                       @Qualifier("systemPrompt") String basePrompt) {
        this.config = config;
        this.llmService = llmService;
        this.toolService = toolService;
        this.tokenCountEstimator = tokenCountEstimator;
        this.hooks = groupHooks(allHooks);
        this.dispatcher = dispatcher;
        this.messageWriter = messageWriter;
        this.stopHandler = stopHandler;
        this.basePrompt = basePrompt;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  主循环
    // ═══════════════════════════════════════════════════════════════════

    public String run(RunContext r) {
        initRun(r);

        // 发出 session.status: running
        r.emit(SessionStatus.running());

        while (true) {
            // 中断检查
            if (r.task().isInterrupted()) {
                return completeExit(r, stopHandler.forceTerminate(
                        r, StopCategory.USER_INTERRUPTED, StopCategory.USER_INTERRUPTED.format()));
            }

            // 步数检查
            if (r.task().turnCount() >= config.getLoop().getMaxSteps()) {
                return completeExit(r, stopHandler.forceTerminate(
                        r, StopCategory.MAX_STEPS_REACHED,
                        StopCategory.MAX_STEPS_REACHED.format(config.getLoop().getMaxSteps())));
            }

            r.nextTurn();

            try {
                LoopAction action = executeTurn(r);
                if (action.isExit()) {
                    return completeExit(r, action.output());
                }
            } catch (Exception e) {
                return completeExit(r, stopHandler.forceTerminate(
                        r, StopCategory.UNEXPECTED_ERROR,
                        StopCategory.UNEXPECTED_ERROR.format(e.getMessage())));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Turn 执行
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction executeTurn(RunContext r) {
        try {
            // ── 阶段 1：准备上下文 ──
            ContextBuilder contextBuilder = buildContext(r);
            r.turn().setPrompt(contextBuilder);
            LoopAction preModel = prepareContext(r, contextBuilder);
            if (preModel.isExit()) {
                return preModel;
            }

            // ── 阶段 2：构建 messages ──
            List<ChatMessage> messages = contextBuilder.build();

            // ── 阶段 3：调用 LLM（流式或同步） ──
            LlmResponse response;
            if (llmService.isStreamEnabled()) {
                response = llmService.streamChat(messages, toolService.getSpecifications(),
                        delta -> r.emit(AgentDelta.text(r.task().turnId(), delta)),
                        delta -> r.emit(AgentDelta.thinking(r.task().turnId(), delta)));
            } else {
                response = llmService.chat(messages, toolService.getSpecifications());
            }
            r.turn().setResponse(response);
            r.session().usageAccum().add(response.usage());

            String text = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            r.turn().setAssistantText(text);
            r.turn().setThinking(response.aiMessage().thinking());
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();

            // ── 阶段 4：PostModel Hooks ──
            r.turn().setPendingToolCalls(requests);
            LoopAction postModel = firePostModelHooks(r);
            if (postModel.isExit()) {
                return postModel;
            }
            if (postModel.isSkip()) {
                return finishSkip(r);
            }

            // ── 阶段 5：无工具调用 → 发出 engine.message 并退出 ──
            if (requests == null || requests.isEmpty()) {
                r.emit(AgentMessage.now(r.task().turnId(), r.turn().messageId(), text));
                return LoopAction.exit(text);
            }

            // ── 阶段 6：Extension Loop ──
            LoopAction extension = executeExtensionLoop(r, requests);
            if (extension.isExit()) {
                return extension;
            }

            // ── 阶段 7：推进熔断冷却 ──
            r.session().circuitBreaker().tickCooldown();

            return LoopAction.CONTINUE;
        } finally {
            // ── 阶段 8：消息回填 ──
            messageWriter.flush(r);
        }
    }

    private LoopAction executeExtensionLoop(RunContext r,
                                            List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        LoopAction preTool = firePreToolHooks(r, requests);
        if (preTool.isExit()) {
            return preTool;
        }
        // 中断检查点：一轮内的工具调用可能很慢，不等下一轮开头才响应
        if (r.task().isInterrupted()) {
            return interruptExit(r);
        }

        // 执行工具（ToolCallDispatcher 内部发出 engine.tool_use 和 engine.tool_result）
        List<ToolCallRecord> results = dispatcher.execute(r);

        // PostTool Hooks
        for (int i = 0; i < requests.size(); i++) {
            if (r.task().isInterrupted()) {
                return interruptExit(r);
            }
            ToolCallRecord result = results.get(i);
            LoopAction postTool = firePostToolHooks(r, requests.get(i).name(), result.success());
            if (postTool.isExit()) {
                return postTool;
            }
        }

        return LoopAction.CONTINUE;
    }

    /** 中断退出：生成终止文本并结束本轮，收尾由 run() 统一处理。 */
    private LoopAction interruptExit(RunContext r) {
        return LoopAction.exit(
                stopHandler.forceTerminate(r, StopCategory.USER_INTERRUPTED, StopCategory.USER_INTERRUPTED.format()));
    }

    /**
     * 跳过本轮后续阶段（PostModel 返回 skip 时），必须清空 pendingToolCalls。
     */
    private LoopAction finishSkip(RunContext r) {
        r.turn().setPendingToolCalls(null);
        r.session().circuitBreaker().tickCooldown();
        return LoopAction.CONTINUE;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  退出处理
    // ═══════════════════════════════════════════════════════════════════

    private String completeExit(RunContext r, String rawOutput) {
        String output = renderMemoryReferences(r, rawOutput);

        // 发出 session.usage
        r.emit(SessionUsage.now(r.task().turnId(), r.turn().messageId(),
                r.session().usageAccum().getPromptTokens(),
                r.session().usageAccum().getCompletionTokens(),
                r.session().usageAccum().getTotalTokens()));

        // 发出 session.status: idle
        r.emit(SessionStatus.idle("task_completed"));

        return output;
    }

    // 任务结束落盘统一收敛到 RunContext#close()，引擎不自行保存快照

    private String renderMemoryReferences(RunContext r, String text) {
        if (text == null || text.isEmpty()) return text;
        return r.session().memoryStore().renderReferences(text);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  初始化
    // ═══════════════════════════════════════════════════════════════════

    private void initRun(RunContext r) {
        r.session().ledger().append(UserMessage.from(r.task().userInput()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文构建
    // ═══════════════════════════════════════════════════════════════════

    private ContextBuilder buildContext(RunContext r) {
        ContextBuilder contextBuilder = new ContextBuilder();
        contextBuilder.setTokenCountEstimator(tokenCountEstimator);
        contextBuilder.setSystemPrompt(basePrompt);
        contextBuilder.setSummary(r.session().compressionState().getLastSummary());
        contextBuilder.setMemories(r.session().memoryStore().renderForInjection());
        contextBuilder.setWindow(r.session().ledger().window());

        contextBuilder.setToolSchemaTokens(estimateToolSchemaTokens());
        contextBuilder.setOutputReserveTokens(config.getLlm().getMaxTokens());
        contextBuilder.setContextMaxTokens(config.getContext().getMaxTokens());
        return contextBuilder;
    }

    private void consumeNudges(RunContext r, ContextBuilder ctx) {
        if (r.task().nudges().isEmpty()) {
            return;
        }
        ctx.setNudges(String.join("\n", r.task().nudges()));
        r.task().nudges().clear();
    }

    /** 工具 Schema 的 token 估算。 */
    private long estimateToolSchemaTokens() {
        return toolService.getSpecifications().size() * TOOL_SCHEMA_TOKENS_ESTIMATE;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction prepareContext(RunContext r, ContextBuilder contextBuilder) {
        HookResult result = HookDispatcher.dispatchPreModel(hooks.preModel, r);
        if (result.isStop()) {
            return exitOf(r, result);
        }
        consumeNudges(r, contextBuilder);
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostModelHooks(RunContext r) {
        HookResult result = HookDispatcher.dispatchPostModel(hooks.postModel, r);
        if (result.isContinue()) {
            return LoopAction.CONTINUE;
        }
        if (result.isSkip()) {
            return LoopAction.SKIP;
        }
        if (result.isStop()) {
            return exitOf(r, result);
        }
        return LoopAction.CONTINUE;
    }

    private LoopAction firePreToolHooks(RunContext r,
                                        List<ToolExecutionRequest> requests) {
        HookResult result = HookDispatcher.dispatchPreTool(hooks.preTool, r, requests);
        if (result.isStop()) {
            return exitOf(r, result);
        }
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostToolHooks(RunContext r, String toolName, boolean success) {
        HookResult result = HookDispatcher.dispatchPostTool(hooks.postTool, r, toolName, success);
        if (result.isStop()) {
            return exitOf(r, result);
        }
        return LoopAction.CONTINUE;
    }

    /** 把 Hook 的 stop 结果翻译成循环退出动作：终止文本由 StopHandler 生成。 */
    private LoopAction exitOf(RunContext r, HookResult result) {
        return LoopAction.exit(
                stopHandler.forceTerminate(r, result.getCategory(), result.getMessage()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 分组
    // ═══════════════════════════════════════════════════════════════════

    /** 将平铺的 Hook 列表按阶段分组并按 order 排序。 */
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

    /** Hook 分组容器。 */
    private record HookBuckets(
            List<Hook.PreModelHook> preModel,
            List<Hook.PostModelHook> postModel,
            List<Hook.PreToolHook> preTool,
            List<Hook.PostToolHook> postTool
    ) {
    }
}
