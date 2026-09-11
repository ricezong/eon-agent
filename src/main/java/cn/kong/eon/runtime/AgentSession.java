package cn.kong.eon.runtime;

import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.context.pipeline.IngestPipeline;
import cn.kong.eon.context.block.CompressionLevel;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.context.policy.CompressionSettings;
import cn.kong.eon.context.summary.LlmContextSummarizer;
import cn.kong.eon.engine.AgentEngine;
import cn.kong.eon.engine.guard.ToolCircuitBreaker;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.postmodel.LoopDetectHook;
import cn.kong.eon.engine.hook.postmodel.ToolValidationHook;
import cn.kong.eon.engine.hook.postmodel.TruncationHook;
import cn.kong.eon.engine.hook.posttool.TodoSnapshotHook;
import cn.kong.eon.engine.hook.posttool.ToolFailureHook;
import cn.kong.eon.engine.hook.premodel.BudgetHook;
import cn.kong.eon.engine.hook.premodel.ContextCompressionHook;
import cn.kong.eon.engine.hook.premodel.TodoContextHook;
import cn.kong.eon.engine.hook.pretool.GateHook;
import cn.kong.eon.engine.exec.ToolCallDispatcher;
import cn.kong.eon.engine.exec.TurnMessageWriter;
import cn.kong.eon.engine.stop.StopHandler;
import cn.kong.eon.logging.Slf4JAgentEventListener;
import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.AgentEventListener;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.store.snapshot.RestoreMode;
import cn.kong.eon.store.snapshot.SessionSnapshot;
import cn.kong.eon.store.artifact.ToolResultArtifactStore;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.index.SessionIndexStore.SessionSummary;
import cn.kong.eon.store.todo.TodoStore;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 会话。封装会话级组件：TranscriptLedger、SessionState、TodoStore、Hook 列表等。
 * 随会话切换而创建/销毁，应用级组件（LlmClient、ToolService、MCP、EonAgent）不在此处。
 * <p>
 * 职责：装配会话级组件 → 组装 {@link SessionContext} → 交给引擎执行 → 释放资源。
 */
public class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    // ── 应用级依赖（运行时使用，不持有所有权）
    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolService toolService;
    private final MemoryStore memoryStore;
    private final ContentCompressor compressor;
    private final String systemPrompt;
    private final ObjectMapper objectMapper;

    // ── 会话级组件
    private final String sessionId;
    private final SessionSummary resumedSession;
    private final TodoStore todoStore;
    private final ToolResultArtifactStore toolResultStore;
    private final SessionSnapshotStore snapshotStore;
    private final IngestPipeline ingestPipeline;
    private final TranscriptLedger transcriptLedger;
    private final String transcriptPath;
    private final SessionState sessionState;
    private final CompressionPolicy compressionPolicy;
    private final ToolCircuitBreaker circuitBreaker;
    private final LoopDetectHook loopDetectHook;
    private final TodoSnapshotHook todoSnapshotHook;
    private final ToolContext toolContext;
    private final ToolCallDispatcher toolDispatcher;
    private final TurnMessageWriter messageWriter;
    private final StopHandler stopHandler;
    private final List<Hook> hooks;
    private final List<AgentEventListener> listeners;

    /** 创建会话上下文，注入应用级依赖。 */
    public AgentSession(AgentConfig appConfig,
                        LlmClient llmClient,
                        ToolService toolService,
                        MemoryStore memoryStore,
                        ContentCompressor compressor,
                        String systemPrompt,
                        SessionSummary resumedSession,
                        List<AgentEventListener> externalListeners,
                        ObjectMapper objectMapper) {
        this.config = appConfig;
        this.llmClient = llmClient;
        this.toolService = toolService;
        this.memoryStore = memoryStore;
        this.compressor = compressor;
        this.systemPrompt = systemPrompt;
        this.resumedSession = resumedSession;
        this.objectMapper = objectMapper;

        // ── 1. 会话 ID 与目录
        this.sessionId = resumedSession != null ? resumedSession.sessionId() : generateSessionId();
        Path sessionBaseDir = Path.of(config.getStorage().getBaseDir()).toAbsolutePath().normalize();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }

        // ── 2. 存储
        this.todoStore = new TodoStore();
        this.toolResultStore = new ToolResultArtifactStore(sessionDir.resolve("tool-results"));
        this.snapshotStore = new SessionSnapshotStore(sessionDir.resolve("state.json"), objectMapper);
        this.ingestPipeline = createContextPipeline();

        // ── 3. 快照 → 回放起点
        SessionSnapshot snapshot = resumedSession != null ? snapshotStore.load() : null;
        RestoreMode mode = RestoreMode.of(snapshot, resumedSession != null ? resumedSession.messageCount() : 0);
        int replayFrom = mode == RestoreMode.RESUME
                ? snapshot.getCompressionState().getReplayFromSeq() : 0;
        if (mode == RestoreMode.LOAD && snapshot != null) {
            log.warn("会话 {} 快照不自洽（水位 {} / 账本 {} 行 / 摘要 {}），改为全量回放",
                    sessionId, snapshot.getCompressionState().getReplayFromSeq(),
                    resumedSession.messageCount(),
                    snapshot.getCompressionState().getLastSummary() != null ? "有" : "无");
        }

        // ── 4. TranscriptLedger（回放）
        Path jsonlPath = sessionDir.resolve("transcript.jsonl");
        this.transcriptLedger = new TranscriptLedger(jsonlPath, ingestPipeline, replayFrom, objectMapper);
        this.transcriptPath = jsonlPath.toAbsolutePath().toString();
        this.sessionState = SessionState.create(sessionId, "");
        if (snapshot != null) {
            restore(snapshot, mode);
            log.info("会话 {} 已恢复: 模式 {}, 回放 #{}~{} 共 {} 条, 摘要 {} 字符, 累计 {} tokens",
                    sessionId, mode, replayFrom, resumedSession.messageCount(),
                    resumedSession.messageCount() - replayFrom,
                    snapshot.getCompressionState().getLastSummary() != null
                            ? snapshot.getCompressionState().getLastSummary().length() : 0,
                    snapshot.getUsageAccum() != null ? snapshot.getUsageAccum().getTotalTokens() : 0);
        } else if (resumedSession != null) {
            log.info("会话 {} 无快照（未调用过 todo_write），按全量历史启动", sessionId);
        } else {
            log.info("会话 {} 已初始化, transcript: {}", sessionId, transcriptPath);
        }

        // ── 5. 工作区（按用途拆分子目录）
        PathResolver pathResolver = createWorkspace(sessionDir);

        // ── 6. 工具上下文
        this.toolContext = new ToolContext(
                todoStore, toolResultStore, memoryStore,
                transcriptLedger, snapshotStore, pathResolver, null);

        // ── 7. 运行时组件
        var ldc = config.getLoopDetect();
        this.circuitBreaker = new ToolCircuitBreaker(ldc);
        this.loopDetectHook = new LoopDetectHook(ldc, circuitBreaker);
        this.todoSnapshotHook = new TodoSnapshotHook(config, snapshotStore, todoStore);
        this.compressionPolicy = createCompressionPolicy();

        // 组装 listeners：Slf4JAgentEventListener + 外部 SSE listeners
        this.listeners = new ArrayList<>();
        this.listeners.add(new Slf4JAgentEventListener());
        this.listeners.addAll(externalListeners != null ? externalListeners : List.of());

        // ── 8. 引擎协作组件（会话级，随会话创建/销毁）
        Consumer<AgentEvent> emitter = event -> {
            for (AgentEventListener l : listeners) {
                try {
                    l.onEvent(event);
                } catch (Exception e) {
                    log.warn("事件监听器异常: {}", e.getMessage(), e);
                }
            }
        };
        this.toolDispatcher = new ToolCallDispatcher(
                toolService, toolContext, emitter, circuitBreaker,
                config.getTools().getParallelism(), objectMapper);
        this.messageWriter = new TurnMessageWriter(transcriptLedger);
        this.stopHandler = new StopHandler(config, emitter);

        // ── 9. Hook 列表
        this.hooks = createHooks();

        log.info("会话 {} 已就绪: {} 个工具, {} 个 hook, {} 个 listener",
                sessionId, toolService.getAllToolNames().size(), hooks.size(), listeners.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  运行
    // ═══════════════════════════════════════════════════════════════════

    /** 运行一轮对话。 */
    public String run(AgentEngine agent, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        sessionState.beginRun(userInput);
        circuitBreaker.reset();
        loopDetectHook.reset();
        todoSnapshotHook.reset();

        log.info("=== 会话 {} 任务开始 ===", sessionState.getSessionId());
        log.info("用户输入: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

        SessionContext ctx = new SessionContext(
                sessionState, transcriptLedger, toolContext,
                circuitBreaker, listeners, hooks,
                toolDispatcher, messageWriter, stopHandler);

        String output = agent.run(ctx);
        log.info("=== 会话 {} 任务结束, 本次 {} 轮, 会话累计 {} tokens ===",
                sessionState.getSessionId(), sessionState.getTurnCount(), sessionState.getUsageAccum().getTotalTokens());
        return output;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话级资源关闭
    // ═══════════════════════════════════════════════════════════════════

    public void close() {
        toolDispatcher.shutdown();
        log.info("会话 {} 资源已释放", sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getter
    // ═══════════════════════════════════════════════════════════════════

    public String getSessionId() { return sessionId; }
    public SessionSummary getResumedSession() { return resumedSession; }
    public TranscriptLedger getTranscriptLedger() { return transcriptLedger; }
    public SessionState getSessionState() { return sessionState; }

    /** 请求中断当前任务。 */
    public void requestInterrupt() {
        sessionState.requestInterrupt();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    private PathResolver createWorkspace(Path sessionDir) {
        for (String sub : List.of("scripts", "download", "upload", "skills", "tool-results")) {
            try {
                Files.createDirectories(sessionDir.resolve(sub));
            } catch (IOException e) {
                throw new RuntimeException("创建工作区子目录失败: " + sub, e);
            }
        }
        return new PathResolver(sessionDir.toAbsolutePath().toString(), config.getTools().isSandboxEnabled());
    }

    private IngestPipeline createContextPipeline() {
        var ctx = config.getContext();
        log.info("入站管线已装配 (落盘阈值 {} 字符, 保留 {} 字符)",
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
        return new IngestPipeline(compressor, toolResultStore,
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
    }

    private CompressionPolicy createCompressionPolicy() {
        var ctxCfg = config.getContext();
        var comp = ctxCfg.getCompression();
        LlmContextSummarizer summarizer = new LlmContextSummarizer(llmClient, transcriptPath,
                ctxCfg.getSummarizeMaxInputChars(), ctxCfg.getSummarizeMaxOutputChars());
        CompressionLevel turnLevel = parseTurnLevel(comp.getTurnLevel());
        log.info("压缩策略已装配: 水位 {}/{}/{} | 轮数周期 {} 档位 {} | 尾部保护 {} 块 | 参数裁剪阈值 {} 字符",
                comp.getSnipWaterLevel(), comp.getPruneWaterLevel(), comp.getSummarizeWaterLevel(),
                comp.getTurnInterval(), turnLevel,
                comp.getTailGuardBlocks(), comp.getArgsPruneMinChars());
        CompressionSettings settings = new CompressionSettings(
                comp.getSnipWaterLevel(), comp.getPruneWaterLevel(), comp.getSummarizeWaterLevel(),
                comp.getTurnInterval(), turnLevel,
                comp.getTailGuardBlocks(), comp.getArgsPruneMinChars(),
                ctxCfg.getSnipKeepChars());
        return new CompressionPolicy(settings, compressor, summarizer);
    }

    /** 解析轮数兜底档位字符串（配置层用 String 承载）。非法值回退为 SNIP。 */
    private CompressionLevel parseTurnLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return CompressionLevel.SNIP;
        }
        try {
            return CompressionLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的压缩档位 '{}'，回退为 SNIP", raw);
            return CompressionLevel.SNIP;
        }
    }

    private List<Hook> createHooks() {
        return List.of(
                new BudgetHook(config),
                new TodoContextHook(todoStore),
                new ContextCompressionHook(compressionPolicy),

                new TruncationHook(),
                new ToolValidationHook(toolService),
                loopDetectHook,

                new GateHook(toolService, config),

                new ToolFailureHook(circuitBreaker),
                todoSnapshotHook
        );
    }

    private void restore(SessionSnapshot cp, RestoreMode mode) {
        if (cp.getUsageAccum() != null) {
            sessionState.setUsageAccum(cp.getUsageAccum());
        }
        if (mode == RestoreMode.RESUME && cp.getCompressionState() != null) {
            sessionState.getCompressionState().setLastSummary(cp.getCompressionState().getLastSummary());
            sessionState.getCompressionState().setReplayFromSeq(cp.getCompressionState().getReplayFromSeq());
        }
        if (cp.getTodoSnapshot() != null && !cp.getTodoSnapshot().isEmpty()) {
            todoStore.replaceAll(cp.getTodoSnapshot(), 0);
        }
    }

    private static String generateSessionId() {
        return UUID.randomUUID().toString();
    }
}
