package cn.kong.eon.session;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.agent.context.ContentTrimmer;
import cn.kong.eon.agent.context.pipeline.ContextPipeline;
import cn.kong.eon.agent.context.policy.CompressionPolicy;
import cn.kong.eon.agent.context.policy.ContextSummarizer;
import cn.kong.eon.agent.exec.ToolHealthTracker;
import cn.kong.eon.agent.hook.postmodel.LoopDetectHook;
import cn.kong.eon.agent.hook.postmodel.ToolValidationHook;
import cn.kong.eon.agent.hook.postmodel.TruncationHook;
import cn.kong.eon.agent.hook.posttool.TodoSnapshotHook;
import cn.kong.eon.agent.hook.posttool.ToolFailureHook;
import cn.kong.eon.agent.hook.premodel.BudgetHook;
import cn.kong.eon.agent.hook.premodel.ContextCompressionHook;
import cn.kong.eon.agent.hook.premodel.TodoHook;
import cn.kong.eon.agent.hook.pretool.GateHook;
import cn.kong.eon.agent.turn.Slf4jTurnListener;
import cn.kong.eon.agent.turn.TurnListener;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.model.RestoreMode;
import cn.kong.eon.model.SessionSnapshot;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.SessionSnapshotStore;
import cn.kong.eon.store.SessionRegistry.SessionSummary;
import cn.kong.eon.store.TodoStore;
import cn.kong.eon.tool.CliInteractionCallback;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 会话上下文。封装会话级组件：JsonlStore、SessionState、TodoStore、EonAgent、Hook 列表等。
 * 随会话切换而创建/销毁，应用级组件（LlmClient、ToolRegistry、MCP）不在此处。
 */
public class SessionContext {

    private static final Logger log = LoggerFactory.getLogger(SessionContext.class);

    private final AgentConfig config;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final ContentTrimmer compressor;
    private final String systemPrompt;
    private final CliInteractionCallback cliInteractionCallback;

    // ── 会话级组件
    private final String sessionId;
    private final SessionSummary resumedSession;
    private final TodoStore todoStore;
    private final ArtifactStore artifactStore;
    private final SessionSnapshotStore snapshotStore;
    private final ContextPipeline contextPipeline;
    private final JsonlStore jsonlStore;
    private final String transcriptPath;
    private final SessionState sessionState;
    private final CompressionPolicy compressionPolicy;
    private final ToolHealthTracker tracker;
    private final LoopDetectHook loopDetectHook;
    private final TodoSnapshotHook todoSnapshotHook;
    private final EonAgent agent;
    private final ToolContext toolContext;

    /**
     * 创建会话上下文。
     *
     * @param appConfig       应用配置
     * @param llmClient       LLM 客户端（应用级，复用）
     * @param toolRegistry    工具注册表（应用级，复用）
     * @param memoryStore     记忆存储（应用级，复用）
     * @param compressor      内容裁剪器（应用级，复用）
     * @param systemPrompt    系统提示词（应用级，复用）
     * @param cliCallback     CLI 交互回调（应用级，复用）
     * @param resumedSession  恢复的会话摘要，新会话为 null
     */
    public SessionContext(AgentConfig appConfig,
                          LlmClient llmClient,
                          ToolRegistry toolRegistry,
                          MemoryStore memoryStore,
                          ContentTrimmer compressor,
                          String systemPrompt,
                          CliInteractionCallback cliCallback,
                          SessionSummary resumedSession) {
        this.config = appConfig;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.compressor = compressor;
        this.systemPrompt = systemPrompt;
        this.cliInteractionCallback = cliCallback;
        this.resumedSession = resumedSession;

        // ── 1. 会话 ID 与目录
        this.sessionId = resumedSession != null ? resumedSession.sessionId() : generateSessionId();
        Path sessionBaseDir = Path.of(config.getStorage().getBaseDir()).toAbsolutePath();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }

        // ── 2. 存储
        this.todoStore = new TodoStore();
        this.artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        this.snapshotStore = new SessionSnapshotStore(sessionDir.resolve("session.json"));
        this.contextPipeline = createContextPipeline();

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

        // ── 4. JsonlStore（回放）
        Path jsonlPath = sessionDir.resolve("transcript.jsonl");
        this.jsonlStore = new JsonlStore(jsonlPath, contextPipeline, replayFrom);
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

        // ── 5. 工作区
        Path workspaceDir = sessionDir.resolve("workspace");
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            throw new RuntimeException("创建工作区目录失败: " + workspaceDir, e);
        }
        PathResolver pathResolver = new PathResolver(workspaceDir.toAbsolutePath().toString(), config.getTools().isSandboxEnabled());

        // ── 6. 工具上下文
        this.toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore,
                jsonlStore, snapshotStore, pathResolver, cliInteractionCallback);

        // ── 7. 运行时组件
        var ldc = config.getLoopDetect();
        this.tracker = new ToolHealthTracker(ldc);
        this.loopDetectHook = new LoopDetectHook(ldc, tracker);
        this.todoSnapshotHook = new TodoSnapshotHook(config, snapshotStore, todoStore);
        this.compressionPolicy = createCompressionPolicy();

        List<TurnListener> listeners = List.of(new Slf4jTurnListener());
        this.agent = new EonAgent(
                config, llmClient, toolRegistry,
                jsonlStore, systemPrompt,
                toolContext, tracker, listeners);
        registerHooks();

        log.info("会话 {} 已就绪: {} 个工具, {} 个 hook",
                sessionId, toolRegistry.getAllToolNames().size(), agent.getHookCount());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  运行
    // ═══════════════════════════════════════════════════════════════════

    /** 运行一轮对话。 */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        sessionState.beginRun(userInput);
        tracker.reset();
        loopDetectHook.reset();
        todoSnapshotHook.reset();

        log.info("=== 会话 {} 任务开始 ===", sessionState.getSessionId());
        log.info("用户输入: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

        String output = agent.run(sessionState);
        log.info("=== 会话 {} 任务结束, 本次 {} 轮, 会话累计 {} tokens ===",
                sessionState.getSessionId(), sessionState.getTurnCount(), sessionState.getUsageAccum().getTotalTokens());
        return output;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话级资源关闭
    // ═══════════════════════════════════════════════════════════════════

    /** 关闭会话级资源（线程池等），不关闭应用级资源（工具、MCP）。 */
    public void close() {
        agent.shutdown();
        log.info("会话 {} 资源已释放", sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getter
    // ═══════════════════════════════════════════════════════════════════

    public String getSessionId() { return sessionId; }
    public SessionSummary getResumedSession() { return resumedSession; }
    public JsonlStore getJsonlStore() { return jsonlStore; }
    public SessionState getSessionState() { return sessionState; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    private ContextPipeline createContextPipeline() {
        var ctx = config.getContext();
        log.info("入站管线已装配 (落盘阈值 {} 字符, 保留 {} 字符)",
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
        return new ContextPipeline(compressor, artifactStore,
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
    }

    private CompressionPolicy createCompressionPolicy() {
        var ctxCfg = config.getContext();
        var comp = ctxCfg.getCompression();
        ContextSummarizer summarizer = new ContextSummarizer(llmClient, transcriptPath, ctxCfg);
        log.info("压缩策略已装配: 水位 {}/{}/{} | 轮数周期 {} 档位 {} | 尾部保护 {} 块 | 参数裁剪阈值 {} 字符",
                comp.getSnipWaterLevel(), comp.getPruneWaterLevel(), comp.getSummarizeWaterLevel(),
                comp.getTurnInterval(), comp.getTurnLevel(),
                comp.getTailGuardBlocks(), comp.getArgsPruneMinChars());
        return new CompressionPolicy(ctxCfg, compressor, summarizer);
    }

    private void registerHooks() {
        agent.addHook(new BudgetHook(config));
        agent.addHook(new TodoHook(todoStore));
        agent.addHook(new ContextCompressionHook(compressionPolicy));

        agent.addHook(new TruncationHook());
        agent.addHook(new ToolValidationHook(toolRegistry));
        agent.addHook(loopDetectHook);

        agent.addHook(new GateHook(toolRegistry, config));

        agent.addHook(new ToolFailureHook(tracker));
        agent.addHook(todoSnapshotHook);
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
        return "eon_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }
}
