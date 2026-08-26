package cn.kong.eon;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.agent.TurnCallback;
import cn.kong.eon.agent.hook.postmodel.LoopDetectHook;
import cn.kong.eon.agent.hook.posttool.CheckpointHook;
import cn.kong.eon.agent.hook.posttool.FailureBreakerHook;
import cn.kong.eon.agent.hook.premodel.BudgetHook;
import cn.kong.eon.agent.hook.premodel.ContextCompactHook;
import cn.kong.eon.agent.hook.premodel.TodoNavigatorHook;
import cn.kong.eon.agent.hook.pretool.GateHook;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.tool.mcp.McpClientManager;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.TodoStore;
import cn.kong.eon.config.HttpConfig;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.tool.builtin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Eon Agent 启动类。
 * <p>
 * 职责：
 * <ol>
 *   <li>加载 agent.yaml 配置和系统提示词</li>
 *   <li>初始化所有组件：LlmClient、Store 集群、ToolRegistry、Hook 链</li>
 *   <li>连接 MCP 服务（如配置）</li>
 *   <li>提供交互式 CLI 循环：读取用户输入 → 运行 Agent → 输出结果</li>
 * </ol>
 * <p>
 * 使用方式：
 * <pre>
 *   java -jar eon-agent.jar
 *   java -Deon.workdir=/path/to/workspace -jar eon-agent.jar
 * </pre>
 */
public class EonApplication {

    private static final Logger log = LoggerFactory.getLogger(EonApplication.class);

    // ===== 配置常量 =====
    private static final String CONFIG_PATH = "config/agent.yaml";
    private static final String SYSTEM_PROMPT_PATH = "prompts/system_prompt.md";
    private static final String DEFAULT_WORKDIR = ".";
    private static final String SESSION_ID_PREFIX = "eon_";

    // ===== 核心组件 =====
    private final AgentConfig config;
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final TodoStore todoStore;
    private final ArtifactStore artifactStore;
    private final CheckpointStore checkpointStore;
    private final MemoryStore memoryStore;
    private final JsonlStore jsonlStore;
    private final ToolResultRenderer resultRenderer;
    private final ToolContext toolContext;
    private final HttpConfig httpConfig;
    private final LoopDetector loopDetector;
    private final EonAgent agent;
    private final String workDir;
    private final String transcriptPath;

    // MCP 客户端（生命周期管理）
    private final java.util.List<McpClientManager> mcpClients = new java.util.ArrayList<>();

    public EonApplication() {
        this(DEFAULT_WORKDIR);
    }

    public EonApplication(String workDir) {
        this.workDir = workDir != null ? workDir : DEFAULT_WORKDIR;
        this.objectMapper = createObjectMapper();

        // 1. 加载配置
        log.info("Loading configuration from classpath: {}", CONFIG_PATH);
        this.config = AgentConfig.loadFromClasspath(CONFIG_PATH);

        // 2. 加载系统提示词
        String systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath());
        log.info("System prompt loaded: {} chars", systemPrompt.length());

        // 3. 初始化共享 HttpClient（从配置注入超时）
        this.httpConfig = new HttpConfig(config.getTools().getHttpConnectTimeoutSeconds());

        // 4. 初始化 LLM 客户端
        this.llmClient = new LlmClient(config);

        // 5. 初始化存储层
        Path sessionBaseDir = resolveSessionBaseDir();
        String sessionId = generateSessionId();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session dir: " + sessionDir, e);
        }
        this.todoStore = new TodoStore();
        this.artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        this.checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"), objectMapper);
        this.memoryStore = new MemoryStore(sessionBaseDir, objectMapper);

        // 6. 创建 JSONL 存储和 transcript 路径
        Path jsonlPath = sessionDir.resolve("transcript.jsonl");
        this.jsonlStore = new JsonlStore(jsonlPath, objectMapper);
        this.transcriptPath = jsonlPath.toAbsolutePath().toString();
        log.info("Session {} initialized, transcript: {}", sessionId, transcriptPath);

        // 7. 初始化工具注册表
        this.toolRegistry = createToolRegistry();

        // 8. 连接 MCP 服务
        connectMcpServers();

        // 9. 创建工具上下文（含共享 PathResolver）
        PathResolver pathResolver = new PathResolver(workDir, config.getTools().isSandboxEnabled());
        this.toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore,
                jsonlStore, checkpointStore, pathResolver, workDir, null);

        // 10. 初始化循环检测器
        var ldc = config.getLoopDetect();
        this.loopDetector = new LoopDetector(
                ldc.getRepeatWarn(), ldc.getRepeatStop(), ldc.getNoProgressSteps(),
                ldc.getFailureWarn(), ldc.getFailureStop());

        // 11. 初始化结果渲染器
        this.resultRenderer = new ToolResultRenderer(artifactStore);

        // 12. 创建 Agent
        this.agent = new EonAgent(
                config, llmClient, toolRegistry,
                resultRenderer, jsonlStore, systemPrompt,
                toolContext, loopDetector, objectMapper);

        // 13. 注册所有 Hook
        registerHooks();

        log.info("EonApplication ready: {} tools, {} hooks",
                toolRegistry.getAllToolNames().size(), agent.getHookCount());
    }

    // ===== 核心运行方法 =====

    /**
     * 运行一轮对话。
     * @param userInput 用户输入文本
     * @return Agent 输出结果
     */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        String sessionId = generateSessionId();
        SessionState state = SessionState.create(sessionId, userInput);

        log.info("=== Session {} started ===", sessionId);
        log.info("User input: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

        String output = agent.run(state);
        log.info("=== Session {} finished, {} turns, {} tokens ===",
                sessionId, state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return output;
    }

    /**
     * 运行一轮对话（带流式回调）。
     */
    public String runStream(String userInput, TurnCallback callback) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        String sessionId = generateSessionId();
        SessionState state = SessionState.create(sessionId, userInput);

        log.info("=== Session {} started (stream) ===", sessionId);
        String output = agent.runStream(state, callback);
        log.info("=== Session {} finished, {} turns, {} tokens ===",
                sessionId, state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return output;
    }

    /** 关闭应用，释放资源。 */
    public void shutdown() {
        log.info("Shutting down EonApplication...");
        agent.shutdown();
        for (McpClientManager mcp : mcpClients) {
            mcp.close();
        }
        log.info("EonApplication shutdown complete.");
    }

    // ===== 初始化方法 =====

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private String loadSystemPrompt(String path) {
        // 先尝试从 classpath 加载
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load system prompt from classpath: {}", path, e);
        }
        // 回退到文件系统
        try {
            Path promptPath = Paths.get(path);
            if (Files.exists(promptPath)) {
                return Files.readString(promptPath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load system prompt from file: {}", path, e);
        }
        log.error("System prompt not found, using empty prompt");
        return "";
    }

    private Path resolveSessionBaseDir() {
        Path base = Path.of(config.getStorage().getBaseDir()).toAbsolutePath();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage base dir: " + base, e);
        }
        return base;
    }

    private String generateSessionId() {
        return SESSION_ID_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry(config.getTools().getWhitelist(), objectMapper);

        // 注册本地工具
        registry.register(ReadFileTool.descriptor());
        registry.register(WriteFileTool.descriptor());
        registry.register(ListDirTool.descriptor());
        registry.register(DownloadFileTool.descriptor(
                config.getTools().getDownload().getMaxFileSizeMb() * 1024 * 1024,
                httpConfig.getClient()));
        registry.register(TodoWriteTool.descriptor(objectMapper));
        registry.register(UpdateMemoryTool.descriptor());

        // web_search 需要千帆 API Key
        String searchApiKey = config.getWebSearch().getApiKey();
        if (searchApiKey != null && !searchApiKey.isBlank()) {
            registry.register(WebSearchTool.descriptor(searchApiKey, objectMapper, httpConfig.getClient()));
        } else {
            log.warn("web_search tool not registered: QIANFAN_API_KEY not configured");
        }

        // web_fetch 带配置
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

        // AskQuestion 工具（CLI 模式无交互回调，但仍注册供 LLM 知晓）
        registry.register(AskQuestionTool.descriptor());

        return registry;
    }

    private void connectMcpServers() {
        var mcpConfig = config.getMcp();
        if (mcpConfig == null || mcpConfig.getServers() == null) return;

        for (AgentConfig.McpServerConfig serverCfg : mcpConfig.getEnabledServers()) {
            if (!serverCfg.isEnabled()) continue;

            String serverKey = serverCfg.getKey() != null ? serverCfg.getKey() : "default";
            String url = serverCfg.getUrl();
            if (url == null || url.isBlank()) {
                log.warn("MCP server '{}' has no URL, skipping", serverKey);
                continue;
            }

            log.info("Connecting to MCP server: key={}, url={}", serverKey, url);
            try {
                McpClientManager mcpClient = new McpClientManager(serverKey, url);
                mcpClient.connect();
                int toolCount = toolRegistry.registerMcpTools(mcpClient, serverCfg.getPermission());
                log.info("MCP server '{}' connected: {} tools registered", serverKey, toolCount);
                mcpClients.add(mcpClient);
            } catch (Exception e) {
                log.error("Failed to connect MCP server '{}': {}", serverKey, e.getMessage(), e);
                // MCP 连接失败不阻止启动，继续其他服务
            }
        }
    }

    private void registerHooks() {
        // PreModel Hooks（按 order 排序）
        agent.addHook(new BudgetHook(config));
        agent.addHook(new TodoNavigatorHook(todoStore));
        agent.addHook(new ContextCompactHook(config, llmClient, transcriptPath));

        // PostModel Hooks
        agent.addHook(new LoopDetectHook(loopDetector, config.getLoopDetect().getStopGraceSteps()));

        // PreTool Hooks
        agent.addHook(new GateHook(toolRegistry, objectMapper, config.getLoopDetect().getStopGraceSteps()));

        // PostTool Hooks
        agent.addHook(new FailureBreakerHook(loopDetector));
        agent.addHook(new CheckpointHook(config, checkpointStore, todoStore));
    }

    // ===== CLI 入口 =====

    public static void main(String[] args) {
        String workDir = System.getProperty("eon.workdir", DEFAULT_WORKDIR);
        log.info("Starting Eon Agent, workDir={}", workDir);

        EonApplication app = new EonApplication(workDir);

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered");
            app.shutdown();
        }));

        // 检查是否有命令行参数（单次运行模式）
        if (args.length > 0) {
            String input = String.join(" ", args);
            log.info("Single-run mode with input: {}", input);
            String result = app.run(input);
            System.out.println("\n" + result);
            app.shutdown();
            return;
        }

        // 交互式 CLI 模式
        runCliLoop(app);
    }

    /**
     * 交互式 CLI 循环。
     * 支持命令：/exit、/quit 退出；/tools 列出工具；/clear 清屏。
     */
    private static void runCliLoop(EonApplication app) {
        java.util.Scanner scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8);

        printWelcome();
        System.out.flush();

        while (true) {
            System.out.print("\nuser> ");
            System.out.flush();
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            // 命令处理
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
            if (input.equalsIgnoreCase("/help")) {
                printHelp();
                continue;
            }

            // 正常对话
            System.out.print("Eon> ");
            System.out.flush();
            try {
                String output = app.run(input);
                System.out.println(output);
            } catch (Exception e) {
                log.error("Agent execution failed", e);
                System.out.println("执行出错: " + e.getMessage());
            }
        }

        scanner.close();
        app.shutdown();
    }

    // ===== CLI 格式化常量 =====

    private static final String LINE_THIN  = "─".repeat(44);
    private static final String LINE_BOLD   = "━".repeat(44);
    private static final String LINE_DOUBLE = "═".repeat(44);

    // ===== CLI 输出 =====

    private static void printWelcome() {
        System.out.println();
        System.out.println("╔" + LINE_DOUBLE + "╗");
        System.out.println("║  Eon — AI 个人助手                          ║");
        System.out.println("║  由孔明灯开发                               ║");
        System.out.println("╚" + LINE_DOUBLE + "╝");
        System.out.println();
        System.out.println(" 直接输入问题开始对话，或使用以下命令：");
        System.out.println();
        System.out.println("   /help    查看帮助");
        System.out.println("   /tools   列出可用工具");
        System.out.println("   /clear   清屏");
        System.out.println("   /exit    退出");
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
        System.out.println("   /help    显示本帮助");
        System.out.println("   /tools   列出可用工具");
        System.out.println("   /clear   清屏");
        System.out.println("   /exit    退出程序");
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
