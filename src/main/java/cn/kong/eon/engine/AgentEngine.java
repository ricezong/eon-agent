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
 * Agent 核心引擎（无状态应用级单例）。每次 {@code run(RunContext)} 接收本次运行的上下文。
 * <p>
 * 每轮执行：PreModel → 构建上下文 → 调用 LLM → PostModel →
 * 工具执行(PreTool→Execute→PostTool) → 回填消息。无工具调用时任务完成。
 * 事件驱动：引擎在关键阶段发出 AgentEvent，统一经 {@code r.emit()} 分发。
 * <p>
 * <b>无状态的含义</b>：不持有任何会话级/任务级/轮次级对象，一切从参数 {@code RunContext} 取；
 * 10 个 Hook 与执行层协作组件也都是构造注入的应用级单例，状态全部外置到
 * {@code SessionScope} / {@code TaskScope} / {@code TurnScope}。
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
    /** Hook 分组在构造期做一次：Hook 集合是应用级不变的，没必要每轮 run 重排。 */
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
        HookBuckets hooks = this.hooks;

        initRun(r);

        // 发出 session.status: running
        r.emit(SessionStatus.running());

        while (true) {
            // 中断检查
            if (r.task().isInterrupted()) {
                return completeExit(r, stopHandler.forceTerminate(
                        r, StopCategory.USER_INTERRUPTED, "用户主动中断"));
            }

            // 步数检查
            if (r.task().turnCount() >= config.getLoop().getMaxSteps()) {
                return completeExit(r, stopHandler.forceTerminate(
                        r, StopCategory.MAX_STEPS_REACHED,
                        StopCategory.MAX_STEPS_REACHED.format(config.getLoop().getMaxSteps())));
            }

            r.nextTurn();

            try {
                LoopAction action = executeTurn(r, hooks);
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

    private LoopAction executeTurn(RunContext r, HookBuckets hooks) {
        try {
            // ── 阶段 1：准备上下文 ──
            ContextBuilder contextBuilder = buildContext(r);
            r.turn().setPrompt(contextBuilder);
            LoopAction preModel = prepareContext(r, hooks, contextBuilder);
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
                        delta -> r.emit(AgentDelta.thinking(r.task().turnId(), delta)),
                        thinking -> r.emit(AgentThinking.now(r.task().turnId(), thinking)));
            } else {
                response = llmService.chat(messages, toolService.getSpecifications());
            }
            r.turn().setResponse(response);
            r.session().usageAccum().add(response.usage());

            String thought = response.aiMessage().text() != null ? response.aiMessage().text() : "";
            r.turn().setAssistantText(thought);
            r.turn().setThinking(response.aiMessage().thinking());
            List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();

            // ── 阶段 4：PostModel Hooks ──
            r.turn().setPendingToolCalls(requests);
            LoopAction postModel = firePostModelHooks(r, hooks);
            if (postModel.isExit()) {
                return postModel;
            }
            if (postModel.isSkip()) {
                return finishSkip(r);
            }

            // ── 阶段 5：无工具调用 → 发出 engine.message 并退出 ──
            if (requests == null || requests.isEmpty()) {
                r.emit(AgentMessage.now(r.task().turnId(), r.turn().messageId(), thought));
                return LoopAction.exit(thought);
            }

            // ── 阶段 6：Extension Loop ──
            LoopAction extension = executeExtensionLoop(r, hooks, requests);
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

    private LoopAction executeExtensionLoop(RunContext r, HookBuckets hooks,
                                            List<ToolExecutionRequest> requests) {
        // PreTool Hooks
        LoopAction preTool = firePreToolHooks(r, hooks, requests);
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
            LoopAction postTool = firePostToolHooks(r, hooks, requests.get(i).name(), result.success());
            if (postTool.isExit()) {
                return postTool;
            }
        }

        return LoopAction.CONTINUE;
    }

    /** 中断退出：生成终止文本并结束本轮，收尾由 run() 统一处理。 */
    private LoopAction interruptExit(RunContext r) {
        return LoopAction.exit(
                stopHandler.forceTerminate(r, StopCategory.USER_INTERRUPTED, "用户主动中断"));
    }

    /**
     * 跳过本轮后续阶段（PostModel 返回 skip 时）。
     * <p>
     * 必须清空 {@code pendingToolCalls}：本轮的工具调用不会被执行，
     * 若带着 tool_calls 落账本却没有对应的 tool_result，下一轮就会出现悬空 tool_calls，
     * OpenAI 兼容接口会直接报错。清理后 flush 只会写入文本部分（无文本则整条不落）。
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

    /**
     * 任务结束落盘已统一收敛到 {@link RunContext#close()}（在 Service 的 {@code finally} 中调用），
     * 引擎不再自行保存快照——否则同一轮会写两遍，且引擎这次不受 {@code snapshot_enabled} 控制，
     * 导致开关关闭时 state.json 照写。
     */

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

    /** 工具 Schema 的 token 估算。工具集是应用级静态的，每次现算，不在引擎里缓存可变状态。 */
    private long estimateToolSchemaTokens() {
        return toolService.getSpecifications().size() * TOOL_SCHEMA_TOKENS_ESTIMATE;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Hook 调度
    // ═══════════════════════════════════════════════════════════════════

    private LoopAction prepareContext(RunContext r, HookBuckets hooks, ContextBuilder contextBuilder) {
        HookResult result = HookDispatcher.dispatchPreModel(hooks.preModel, r);
        if (result != null && result.isStop()) {
            return exitOf(r, result);
        }
        consumeNudges(r, contextBuilder);
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostModelHooks(RunContext r, HookBuckets hooks) {
        HookResult result = HookDispatcher.dispatchPostModel(hooks.postModel, r);
        if (result == null) {
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

    private LoopAction firePreToolHooks(RunContext r, HookBuckets hooks,
                                        List<ToolExecutionRequest> requests) {
        HookResult result = HookDispatcher.dispatchPreTool(hooks.preTool, r, requests);
        if (result != null && result.isStop()) {
            return exitOf(r, result);
        }
        return LoopAction.CONTINUE;
    }

    private LoopAction firePostToolHooks(RunContext r, HookBuckets hooks, String toolName, boolean success) {
        HookResult result = HookDispatcher.dispatchPostTool(hooks.postTool, r, toolName, success);
        if (result != null && result.isStop()) {
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

    /**
     * 将平铺的 Hook 列表按阶段分组并排序。
     * Spring 注入顺序不保证，但这里按 {@code order()} 重排，因此免疫注入顺序。
     */
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
