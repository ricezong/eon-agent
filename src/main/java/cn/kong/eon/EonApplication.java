package cn.kong.eon;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.agent.context.ContentTrimmer;
import cn.kong.eon.agent.context.pipeline.ContextPipeline;
import cn.kong.eon.agent.context.policy.CompressionPolicy;
import cn.kong.eon.agent.context.policy.ContextSummarizer;
import cn.kong.eon.agent.hook.postmodel.LoopDetectHook;
import cn.kong.eon.agent.hook.postmodel.ToolValidationHook;
import cn.kong.eon.agent.hook.postmodel.TruncationHook;
import cn.kong.eon.agent.hook.posttool.TodoSnapshotHook;
import cn.kong.eon.agent.hook.posttool.ToolFailureHook;
import cn.kong.eon.agent.hook.premodel.BudgetHook;
import cn.kong.eon.agent.hook.premodel.ContextCompressionHook;
import cn.kong.eon.agent.hook.premodel.TodoHook;
import cn.kong.eon.agent.hook.pretool.GateHook;
import cn.kong.eon.agent.exec.ToolHealthTracker;
import cn.kong.eon.agent.turn.Slf4jTurnListener;
import cn.kong.eon.agent.turn.TurnListener;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.tool.mcp.McpClientManager;
import cn.kong.eon.model.SessionSnapshot;
import cn.kong.eon.model.RestoreMode;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.SessionSnapshotStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.SessionRegistry;
import cn.kong.eon.store.SessionRegistry.SessionSummary;
import cn.kong.eon.store.TodoStore;
import cn.kong.eon.config.HttpConfig;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.CliInteractionCallback;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.builtin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Agent 启动类。负责配置加载、组件初始化、工具注册、上下文架构装配和 Hook 注册。
 */
public class EonApplication {

    private static final Logger log = LoggerFactory.getLogger(EonApplication.class);

    private static final String CONFIG_PATH = "config/agent.yaml";
    private static final String DEFAULT_WORKDIR = ".";
    /** 取最近活跃会话的选择器关键字。 */
    private static final String SELECTOR_LAST = "last";
    /** /history 默认展示块数。 */
    private static final int DEFAULT_HISTORY_LINES = 20;

    private final AgentConfig config;
    /** 内容压缩器。 */
    private final ContentTrimmer compressor;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final TodoStore todoStore;
    private final ArtifactStore artifactStore;
    private final SessionSnapshotStore snapshotStore;
    private final MemoryStore memoryStore;
    private final JsonlStore jsonlStore;
    private final ContextPipeline contextPipeline;
    private final CompressionPolicy compressionPolicy;
    private final ToolContext toolContext;
    private final HttpConfig httpConfig;
    private final ToolHealthTracker tracker;
    private final LoopDetectHook loopDetectHook;
    private final TodoSnapshotHook todoSnapshotHook;
    private final EonAgent agent;
    private final String workDir;
    private final String transcriptPath;
    /** 会话注册表。 */
    private final SessionRegistry sessionRegistry;
    /** 恢复的会话摘要，新会话为 null。 */
    private final SessionSummary resumedSession;
    /** 会话级状态。 */
    private final SessionState sessionState;
    /** CLI 交互回调。 */
    private final CliInteractionCallback cliInteractionCallback;

    /** MCP 客户端列表。 */
    private final java.util.List<McpClientManager> mcpClients = new java.util.ArrayList<>();

    public EonApplication() {
        this(DEFAULT_WORKDIR);
    }

    public EonApplication(String workDir) {
        this(workDir, null);
    }

    /**
     * @param workDir        工作目录
     * @param resumeSelector 恢复选择器：null/空=新会话；"last"=最近活跃；其余=id 或前缀匹配
     */
    public EonApplication(String workDir, String resumeSelector) {
        this(workDir, resumeSelector, null);
    }

    /** 切换会话时复用同一个 Scanner。 */
    EonApplication(String workDir, String resumeSelector, java.util.Scanner sharedScanner) {
        this.workDir = workDir != null ? workDir : DEFAULT_WORKDIR;
        this.compressor = new ContentTrimmer();

        log.info("从 classpath 加载配置: {}", CONFIG_PATH);
        this.config = AgentConfig.loadFromClasspath(CONFIG_PATH);

        String systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath());
        log.info("系统提示词已加载: {} 字符", systemPrompt.length());

        this.httpConfig = new HttpConfig();
        this.llmClient = new LlmClient(config);

        Path sessionBaseDir = resolveSessionBaseDir();
        this.sessionRegistry = new SessionRegistry(sessionBaseDir);
        this.resumedSession = resolveResumed(resumeSelector);
        String sessionId = resumedSession != null ? resumedSession.sessionId() : generateSessionId();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }
        this.todoStore = new TodoStore();
        this.artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        this.snapshotStore = new SessionSnapshotStore(sessionDir.resolve("session.json"));
        this.memoryStore = new MemoryStore(sessionBaseDir);

        // 快照要在 JsonlStore 之前读回：回放起点（压缩水位线）取自快照
        SessionSnapshot snapshot = resumedSession != null ? snapshotStore.load() : null;
        RestoreMode mode = RestoreMode.of(snapshot, resumedSession != null ? resumedSession.messageCount() : 0);
        int replayFrom = mode == RestoreMode.RESUME
                ? snapshot.getCompressionState().getKeepFromMessage() : 0;
        if (mode == RestoreMode.LOAD && snapshot != null) {
            log.warn("会话 {} 快照不自洽（水位 {} / 账本 {} 行 / 摘要 {}），改为全量回放",
                    sessionId, snapshot.getCompressionState().getKeepFromMessage(),
                    resumedSession.messageCount(),
                    snapshot.getCompressionState().getLastSummary() != null ? "有" : "无");
        }

        this.toolRegistry = createToolRegistry();
        connectMcpServers();
        this.contextPipeline = createContextPipeline();

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

        Path workspaceDir = sessionDir.resolve("workspace");
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            throw new RuntimeException("创建工作区目录失败: " + workspaceDir, e);
        }
        String sessionWorkDir = workspaceDir.toAbsolutePath().toString();
        PathResolver pathResolver = new PathResolver(sessionWorkDir, config.getTools().isSandboxEnabled());
        this.cliInteractionCallback = sharedScanner != null
                ? new CliInteractionCallback(sharedScanner)
                : new CliInteractionCallback(new java.util.Scanner(System.in, StandardCharsets.UTF_8));
        this.toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore,
                jsonlStore, snapshotStore, pathResolver, cliInteractionCallback);

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

        log.info("EonApplication 就绪: {} 个工具, {} 个 hook",
                toolRegistry.getAllToolNames().size(), agent.getHookCount());
    }

    /**
     * 从快照恢复会话状态。累计 token 与 todo 无条件恢复；
     * 摘要与回放水位线只在 RESUME 模式下恢复，LOAD 模式下快照不自洽不能照搬。
     */
    private void restore(SessionSnapshot cp, RestoreMode mode) {
        if (cp.getUsageAccum() != null) {
            sessionState.setUsageAccum(cp.getUsageAccum());
        }
        if (mode == RestoreMode.RESUME && cp.getCompressionState() != null) {
            sessionState.getCompressionState().setLastSummary(cp.getCompressionState().getLastSummary());
            sessionState.getCompressionState().setKeepFromMessage(cp.getCompressionState().getKeepFromMessage());
        }
        if (cp.getTodoSnapshot() != null && !cp.getTodoSnapshot().isEmpty()) {
            todoStore.replaceAll(cp.getTodoSnapshot(), 0);
        }
    }

    /**
     * 解析恢复选择器：null/空=新会话；"last"=最近活跃；其余=id 或前缀匹配。
     */
    private SessionSummary resolveResumed(String selector) {
        if (selector == null || selector.isBlank()) return null;
        var found = SELECTOR_LAST.equalsIgnoreCase(selector)
                ? sessionRegistry.last()
                : sessionRegistry.find(selector);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("未找到会话: " + selector + "（可用 /sessions 查看历史会话）");
        }
        return found.get();
    }


    /** 运行一轮对话。 */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        // 任务边界：会话级状态与循环检测状态必须在同一点重置，
        // 否则上一任务熔断的工具在新任务里仍会被拦截而永久不可用。
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

    /** 关闭应用，释放资源。 */
    public void shutdown() {
        log.info("正在关闭 EonApplication...");
        agent.shutdown();
        for (McpClientManager mcp : mcpClients) {
            mcp.close();
        }
        log.info("EonApplication 已关闭。");
    }

    /** 加载系统提示词，优先 classpath，回退文件系统。 */
    private String loadSystemPrompt(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("从 classpath 加载系统提示词失败: {}", path, e);
        }
        try {
            Path promptPath = Paths.get(path);
            if (Files.exists(promptPath)) {
                return Files.readString(promptPath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("从文件加载系统提示词失败: {}", path, e);
        }
        log.error("系统提示词未找到，使用空提示词");
        return "";
    }

    /** 创建存储根目录。 */
    private Path resolveSessionBaseDir() {
        Path base = Path.of(config.getStorage().getBaseDir()).toAbsolutePath();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new RuntimeException("创建存储根目录失败: " + base, e);
        }
        return base;
    }

    /** 生成会话 ID。 */
    private String generateSessionId() {
        return SessionRegistry.SESSION_ID_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  上下文架构装配
    // ═══════════════════════════════════════════════════════════════════

    /** 创建入站管线：大结果落盘。 */
    private ContextPipeline createContextPipeline() {
        var ctx = config.getContext();
        log.info("入站管线已装配 (落盘阈值 {} 字符, 保留 {} 字符)",
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());

        return new ContextPipeline(compressor, artifactStore,
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
    }

    /** 创建压缩策略。 */
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

    /** 创建工具注册表并注册内置工具。 */
    private ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry(
                config.getTools().getWhitelist());

        registry.register(ReadFileTool.descriptor());
        registry.register(WriteFileTool.descriptor());
        registry.register(ListDirTool.descriptor());
        registry.register(DownloadFileTool.descriptor(
                config.getTools().getDownload().getMaxFileSizeMb() * 1024 * 1024,
                httpConfig.getClient()));
        registry.register(TodoWriteTool.descriptor());
        registry.register(UpdateMemoryTool.descriptor());

        var searchCfg = config.getWebSearch();
        String searchApiKey = searchCfg.getApiKey();
        if (searchApiKey != null && !searchApiKey.isBlank()) {
            registry.register(WebSearchTool.descriptor(
                    searchApiKey,
                    searchCfg.getSearchSource(),
                    searchCfg.getTopK(),
                    searchCfg.getRecencyFilter(),
                    httpConfig.getClient()));
        } else {
            log.warn("web_search 工具未注册: QIANFAN_API_KEY 未配置");
        }

        var wfCfg = config.getTools().getWebFetch();
        if (wfCfg != null) {
            registry.register(WebFetchTool.descriptor(
                    wfCfg.getMaxContentLength(),
                    wfCfg.getCacheTtlMinutes(),
                    wfCfg.getCacheMaxEntries(),
                    httpConfig.getClient()));
        } else {
            registry.register(WebFetchTool.descriptor());
        }

        registry.register(AskQuestionTool.descriptor());

        return registry;
    }

    /** 连接已启用的 MCP 服务并注册工具。 */
    private void connectMcpServers() {
        var mcpConfig = config.getMcp();
        if (mcpConfig == null || mcpConfig.getServers() == null) return;

        for (AgentConfig.McpServerConfig serverCfg : mcpConfig.getEnabledServers()) {
            if (!serverCfg.isEnabled()) continue;

            String serverKey = serverCfg.getKey() != null ? serverCfg.getKey() : "default";
            String url = serverCfg.getUrl();
            if (url == null || url.isBlank()) {
                log.warn("MCP 服务 '{}' 未配置 URL，跳过", serverKey);
                continue;
            }

            log.info("连接 MCP 服务: key={}, url={}", serverKey, url);
            try {
                McpClientManager mcpClient = new McpClientManager(serverKey, url);
                mcpClient.connect();
                int toolCount = toolRegistry.registerMcpTools(mcpClient, serverCfg.getPermission());
                log.info("MCP 服务 '{}' 已连接: 注册 {} 个工具", serverKey, toolCount);
                mcpClients.add(mcpClient);
            } catch (Exception e) {
                log.error("连接 MCP 服务 '{}' 失败: {}", serverKey, e.getMessage(), e);
                // MCP 连接失败不阻止启动
            }
        }
    }

    /** 注册所有 Hook。 */
    private void registerHooks() {
        // PreModel Hooks
        agent.addHook(new BudgetHook(config));
        agent.addHook(new TodoHook(todoStore));
        agent.addHook(new ContextCompressionHook(compressionPolicy));

        // PostModel Hooks
        agent.addHook(new TruncationHook());
        agent.addHook(new ToolValidationHook(toolRegistry));
        agent.addHook(loopDetectHook);

        // PreTool Hooks
        agent.addHook(new GateHook(toolRegistry, config));

        // PostTool Hooks
        agent.addHook(new ToolFailureHook(tracker));
        agent.addHook(todoSnapshotHook);
    }

    public static void main(String[] args) {
        String workDir = System.getProperty("eon.workdir", DEFAULT_WORKDIR);
        log.info("启动 Eon Agent, 工作目录={}", workDir);

        // 解析恢复入口：--resume <id|前缀> 或 --last（最近活跃会话），剩余参数拼为单次输入
        String resumeSelector = null;
        String[] rest = args;
        if (args.length >= 2 && "--resume".equalsIgnoreCase(args[0])) {
            resumeSelector = args[1];
            rest = java.util.Arrays.copyOfRange(args, 2, args.length);
        } else if (args.length >= 1 && "--last".equalsIgnoreCase(args[0])) {
            resumeSelector = SELECTOR_LAST;
            rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        }

        EonApplication app;
        try {
            app = new EonApplication(workDir, resumeSelector);
        } catch (IllegalArgumentException e) {
            System.out.println("启动失败: " + e.getMessage());
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭钩子已触发");
            app.shutdown();
        }));

        if (rest.length > 0) {
            String input = String.join(" ", rest);
            log.info("单次运行模式，输入: {}", input);
            String result = app.run(input);
            System.out.println("\n" + result);
            app.shutdown();
            return;
        }

        runCliLoop(app);
    }

    /** 交互式 CLI 循环。 */
    private static void runCliLoop(EonApplication app) {
        java.util.Scanner scanner = app.cliInteractionCallback.getScanner();

        printWelcome(app);
        System.out.flush();

        while (true) {
            System.out.print("\nuser> ");
            System.out.flush();
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) {
                System.out.println("再见！");
                break;
            }
            if (input.equalsIgnoreCase("/tools")) {
                printTools(app);
                continue;
            }
            if (input.equalsIgnoreCase("/clear")) {
                System.out.print("\033[2J\033[H");
                System.out.flush();
                continue;
            }
            if (input.equalsIgnoreCase("/sessions")) {
                printSessions(app);
                continue;
            }
            if (input.toLowerCase().startsWith("/resume")) {
                String[] parts = input.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("用法: /resume <序号|会话id|前缀|last>（可用 /sessions 查看会话列表）");
                    continue;
                }
                String selector = resolveSelector(app, parts[1]);
                if (selector == null) continue;
                app = switchSession(app, scanner, selector);
                if (app == null) continue;
                printWelcome(app);
                continue;
            }
            if (input.equalsIgnoreCase("/new")) {
                app = switchSession(app, scanner, null);
                if (app == null) continue;
                printWelcome(app);
                continue;
            }
            if (input.toLowerCase().startsWith("/history")) {
                String[] parts = input.split("\\s+");
                printHistory(app, parts.length >= 2 ? parseCount(parts[1]) : DEFAULT_HISTORY_LINES);
                continue;
            }
            if (input.toLowerCase().startsWith("/delete")) {
                String[] parts = input.split("\\s+");
                if (parts.length < 2) {
                    System.out.println("用法: /delete <序号|会话id>（可用 /sessions 查看会话列表）");
                    continue;
                }
                String selector = resolveSelector(app, parts[1]);
                if (selector == null) continue;
                app.sessionRegistry.delete(selector);
                System.out.println("已删除会话: " + selector + "（磁盘文件保留，可从 data/deleted.json 中移除该条目恢复）");
                continue;
            }
            if (input.equalsIgnoreCase("/help")) {
                printHelp();
                continue;
            }

            System.out.print("Eon> ");
            System.out.flush();
            try {
                String output = app.run(input);
                System.out.println(output);
            } catch (Exception e) {
                log.error("Agent 执行失败", e);
                System.out.println("执行出错: " + e.getMessage());
            }
        }

        scanner.close();
        app.shutdown();
    }

    /** 切换会话：关掉旧应用后重建。 */
    private static EonApplication switchSession(EonApplication old, java.util.Scanner scanner, String selector) {
        try {
            old.shutdown();
            return new EonApplication(old.workDir, selector, scanner);
        } catch (Exception e) {
            log.error("切换会话失败: {}", selector, e);
            System.out.println("切换会话失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析 CLI 参数为会话 id：纯数字视为列表序号，其余原样交给注册表匹配。
     */
    private static String resolveSelector(EonApplication app, String token) {
        if (!token.matches("\\d+")) return token;
        var sessions = app.sessionRegistry.list();
        int index = Integer.parseInt(token) - 1;
        if (index < 0 || index >= sessions.size()) {
            System.out.println("序号超出范围: " + token + "（当前共 " + sessions.size() + " 个会话）");
            return null;
        }
        return sessions.get(index).sessionId();
    }

    private static int parseCount(String token) {
        try {
            int n = Integer.parseInt(token);
            return n > 0 ? n : DEFAULT_HISTORY_LINES;
        } catch (NumberFormatException e) {
            return DEFAULT_HISTORY_LINES;
        }
    }

    /** 列出历史会话。 */
    private static void printSessions(EonApplication app) {
        var sessions = app.sessionRegistry.list();
        System.out.println();
        System.out.println(" 历史会话");
        System.out.println(" " + LINE_BOLD);
        System.out.println();
        if (sessions.isEmpty()) {
            System.out.println("   （无历史会话）");
        }
        for (int i = 0; i < sessions.size(); i++) {
            var s = sessions.get(i);
            System.out.printf("  #%-2d %-32s %-10s %5d 条  %-8s %s%n",
                    i + 1, clip(s.title(), 32), relativeTime(s.lastActivityAt()), s.messageCount(),
                    s.hasSnapshot() ? "[可恢复]" : "[无快照]",
                    s.summaryPreview() != null ? s.summaryPreview() : "");
        }
        System.out.println();
        System.out.println(" /resume <序号|id|前缀|last> 恢复 · /delete <序号|id> 删除");
        System.out.println();
    }

    /** 展示当前上下文窗口最近 n 块。 */
    private static void printHistory(EonApplication app, int n) {
        var blocks = app.jsonlStore.window().blocks();
        System.out.println();
        System.out.println(" 当前上下文（共 " + blocks.size() + " 块，显示最近 " + Math.min(n, blocks.size()) + " 块）");
        System.out.println(" " + LINE_BOLD);
        System.out.println();
        for (int i = Math.max(0, blocks.size() - n); i < blocks.size(); i++) {
            var b = blocks.get(i);
            String prefix = switch (b.kind()) {
                case USER_INPUT -> "[用户]";
                case AI_TEXT -> "[助手]";
                case TOOL_ARGS -> "[调用:" + b.toolName() + "]";
                case TOOL_RESULT -> "[结果:" + b.toolName() + "]";
            };
            String text = b.text() == null ? "" : b.text().replace("\n", " ").trim();
            System.out.printf("  %-18s %s%n", clip(prefix, 18), clip(text, 60));
        }
        System.out.println();
    }

    /** 相对时间格式化。 */
    private static String relativeTime(Instant at) {
        long minutes = Duration.between(at, Instant.now()).toMinutes();
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + " 分钟前";
        if (minutes < 60 * 24) return (minutes / 60) + " 小时前";
        if (minutes < 60 * 24 * 7) return (minutes / (60 * 24)) + " 天前";
        return DateTimeFormatter.ofPattern("M-dd HH:mm")
                .format(LocalDateTime.ofInstant(at, ZoneId.systemDefault()));
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static final String LINE_BOLD = "━".repeat(44);
    private static final String LINE_DOUBLE = "═".repeat(44);

    private static void printWelcome(EonApplication app) {
        System.out.println();
        System.out.println("╔" + LINE_DOUBLE + "╗");
        System.out.println("║  Eon — AI 个人助手                          ║");
        System.out.println("║  由孔明灯开发                               ║");
        System.out.println("╚" + LINE_DOUBLE + "╝");
        System.out.println();
        System.out.println(" 会话: " + app.sessionState.getSessionId());
        if (app.resumedSession != null) {
            var s = app.resumedSession;
            System.out.println(" 恢复: " + s.title() + " — " + relativeTime(s.lastActivityAt())
                    + " · " + s.messageCount() + " 条 · " + (s.hasSnapshot() ? "有快照" : "无快照"));
        }
        System.out.println();
        System.out.println(" 直接输入问题开始对话，或使用以下命令：");
        System.out.println();
        System.out.println("   /help     查看帮助");
        System.out.println("   /tools    列出可用工具");
        System.out.println("   /sessions 列出历史会话");
        System.out.println("   /resume   恢复指定会话");
        System.out.println("   /new      新建会话");
        System.out.println("   /history  查看当前上下文");
        System.out.println("   /clear    清屏");
        System.out.println("   /exit     退出");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println();
        System.out.println(" Eon 个人助手 — 帮助");
        System.out.println(" " + LINE_BOLD);
        System.out.println();
        System.out.println(" 直接输入文本即可与助手对话，助手能够：");
        System.out.println("   • 搜索网络、抓取网页");
        System.out.println("   • 读取/写入文件、下载文件");
        System.out.println("   • 管理待办事项、记忆你的偏好");
        System.out.println();
        System.out.println(" 命令：");
        System.out.println("   /help     显示本帮助");
        System.out.println("   /tools    列出可用工具");
        System.out.println("   /sessions 列出历史会话（按最后活跃时间倒序，带序号）");
        System.out.println("   /resume <序号|id|前缀|last>  恢复会话，复用其摘要与累积 token");
        System.out.println("   /new      新建一个会话");
        System.out.println("   /history [n]  查看当前上下文的最后 n 块（默认 " + DEFAULT_HISTORY_LINES + "）");
        System.out.println("   /delete <序号|id>  删除会话（软删除，磁盘文件保留）");
        System.out.println("   /clear    清屏");
        System.out.println("   /exit     退出程序");
        System.out.println();
        System.out.println(" 启动参数：--resume <id|前缀> 恢复指定会话 · --last 恢复最近活跃的会话");
        System.out.println("           不带参数启动则是新会话");
        System.out.println();
    }

    private static void printTools(EonApplication app) {
        var names = app.toolRegistry.getAllToolNames();
        System.out.println();
        System.out.println(" 可用工具列表（" + names.size() + "）");
        System.out.println(" " + LINE_BOLD);
        System.out.println();

        for (String name : names) {
            var perm = app.toolRegistry.getPermission(name);
            String permTag = perm != null ? formatPermission(perm) : "?";
            boolean isMcp = app.toolRegistry.isMcpTool(name);
            var desc = app.toolRegistry.get(name);
            String description = desc != null ? desc.getDescription() : "";
            String source = isMcp ? "MCP" : "";
            System.out.printf("  %-14s [%s] %s  %s%n", name, permTag, description != null ? description : "", source);
        }
        System.out.println();
        System.out.println(" 权限: R=只读 W=受限写 D=危险操作    MCP=远程工具");
        System.out.println();
    }

    private static String formatPermission(cn.kong.eon.model.ToolPermission perm) {
        return switch (perm) {
            case READONLY -> "R";
            case RESTRICTED_WRITE -> "W";
            case DESTRUCTIVE -> "D";
        };
    }
}
