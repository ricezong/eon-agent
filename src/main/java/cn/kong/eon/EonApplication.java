package cn.kong.eon;

import cn.kong.eon.agent.EonAgent;
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
import cn.kong.eon.tool.CliInteractionCallback;
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
 * Eon Agent 启动类。加载配置、初始化组件、连接 MCP 服务、提供交互式 CLI 循环。
 */
public class EonApplication {

    private static final Logger log = LoggerFactory.getLogger(EonApplication.class);

    private static final String CONFIG_PATH = "config/agent.yaml";
    private static final String SYSTEM_PROMPT_PATH = "prompts/system_prompt.md";
    private static final String DEFAULT_WORKDIR = ".";
    private static final String SESSION_ID_PREFIX = "eon_";

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
    /** 会话级状态：整个会话共享，跨多次用户输入保留预算/压缩等累积状态 */
    private final SessionState sessionState;
    /** CLI 交互回调（共享 Scanner） */
    private final CliInteractionCallback cliInteractionCallback;

    /** MCP 客户端（生命周期管理） */
    private final java.util.List<McpClientManager> mcpClients = new java.util.ArrayList<>();

    public EonApplication() {
        this(DEFAULT_WORKDIR);
    }

    public EonApplication(String workDir) {
        this.workDir = workDir != null ? workDir : DEFAULT_WORKDIR;
        this.objectMapper = createObjectMapper();

        log.info("从 classpath 加载配置: {}", CONFIG_PATH);
        this.config = AgentConfig.loadFromClasspath(CONFIG_PATH);

        String systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath());
        log.info("系统提示词已加载: {} 字符", systemPrompt.length());

        this.httpConfig = new HttpConfig();
        this.llmClient = new LlmClient(config);

        Path sessionBaseDir = resolveSessionBaseDir();
        String sessionId = generateSessionId();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }
        this.todoStore = new TodoStore();
        this.artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        this.checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"), objectMapper);
        this.memoryStore = new MemoryStore(sessionBaseDir, objectMapper);

        Path jsonlPath = sessionDir.resolve("transcript.jsonl");
        this.jsonlStore = new JsonlStore(jsonlPath, objectMapper);
        this.transcriptPath = jsonlPath.toAbsolutePath().toString();
        this.sessionState = SessionState.create(sessionId, "");
        log.info("会话 {} 已初始化, transcript: {}", sessionId, transcriptPath);

        this.toolRegistry = createToolRegistry();

        connectMcpServers();

        Path workspaceDir = sessionDir.resolve("workspace");
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            throw new RuntimeException("创建工作区目录失败: " + workspaceDir, e);
        }
        String sessionWorkDir = workspaceDir.toAbsolutePath().toString();
        PathResolver pathResolver = new PathResolver(sessionWorkDir, config.getTools().isSandboxEnabled());
        this.cliInteractionCallback = new CliInteractionCallback(new java.util.Scanner(System.in, StandardCharsets.UTF_8));
        this.toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore,
                jsonlStore, checkpointStore, pathResolver, cliInteractionCallback);

        var ldc = config.getLoopDetect();
        this.loopDetector = new LoopDetector(
                ldc.getRepeatWarn(), ldc.getRepeatStop(), ldc.getNoProgressSteps(),
                ldc.getFailureWarn(), ldc.getFailureStop());

        this.resultRenderer = new ToolResultRenderer(artifactStore, config.getContext().getSnipKeepChars());

        this.agent = new EonAgent(
                config, llmClient, toolRegistry,
                resultRenderer, jsonlStore, systemPrompt,
                toolContext, loopDetector, objectMapper);

        registerHooks();

        log.info("EonApplication 就绪: {} 个工具, {} 个 hook",
                toolRegistry.getAllToolNames().size(), agent.getHookCount());
    }

    /**
     * 运行一轮对话（同一会话内复用会话级状态）。
     *
     * @param userInput 用户输入文本
     * @return Agent 输出结果
     */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }

        sessionState.beginRun(userInput);

        log.info("=== 会话 {} 任务开始 ===", sessionState.getSessionId());
        log.info("用户输入: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

        String output = agent.run(sessionState);
        log.info("=== 会话 {} 任务结束, 本次 {} 轮, 会话累计 {} tokens ===",
                sessionState.getSessionId(), sessionState.getTurnCount(), sessionState.getUsageAccum().getTotalTokens());
        return output;
    }

    /**
     * 关闭应用，释放资源。
     */
    public void shutdown() {
        log.info("正在关闭 EonApplication...");
        agent.shutdown();
        for (McpClientManager mcp : mcpClients) {
            mcp.close();
        }
        log.info("EonApplication 已关闭。");
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

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

    private Path resolveSessionBaseDir() {
        Path base = Path.of(config.getStorage().getBaseDir()).toAbsolutePath();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new RuntimeException("创建存储根目录失败: " + base, e);
        }
        return base;
    }

    private String generateSessionId() {
        return SESSION_ID_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry(config.getTools().getWhitelist(), objectMapper);

        registry.register(ReadFileTool.descriptor());
        registry.register(WriteFileTool.descriptor());
        registry.register(ListDirTool.descriptor());
        registry.register(DownloadFileTool.descriptor(
                config.getTools().getDownload().getMaxFileSizeMb() * 1024 * 1024,
                httpConfig.getClient()));
        registry.register(TodoWriteTool.descriptor(objectMapper));
        registry.register(UpdateMemoryTool.descriptor());

        String searchApiKey = config.getWebSearch().getApiKey();
        if (searchApiKey != null && !searchApiKey.isBlank()) {
            registry.register(WebSearchTool.descriptor(searchApiKey, objectMapper, httpConfig.getClient()));
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

    private void registerHooks() {
        // PreModel Hooks
        agent.addHook(new BudgetHook(config));
        agent.addHook(new TodoNavigatorHook(todoStore));
        agent.addHook(new ContextCompactHook(config, llmClient, transcriptPath, jsonlStore));

        // PostModel Hooks
        agent.addHook(new LoopDetectHook(loopDetector, config.getLoopDetect().getStopGraceSteps()));

        // PreTool Hooks
        agent.addHook(new GateHook(toolRegistry));

        // PostTool Hooks
        agent.addHook(new FailureBreakerHook(loopDetector));
        agent.addHook(new CheckpointHook(config, checkpointStore, todoStore));
    }

    public static void main(String[] args) {
        String workDir = System.getProperty("eon.workdir", DEFAULT_WORKDIR);
        log.info("启动 Eon Agent, 工作目录={}", workDir);

        EonApplication app = new EonApplication(workDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭钩子已触发");
            app.shutdown();
        }));

        if (args.length > 0) {
            String input = String.join(" ", args);
            log.info("单次运行模式，输入: {}", input);
            String result = app.run(input);
            System.out.println("\n" + result);
            app.shutdown();
            return;
        }

        runCliLoop(app);
    }

    /**
     * 交互式 CLI 循环。支持 /exit、/quit 退出，/tools 列出工具，/clear 清屏。
     */
    private static void runCliLoop(EonApplication app) {
        java.util.Scanner scanner = app.cliInteractionCallback.getScanner();

        printWelcome();
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

    private static final String LINE_BOLD = "━".repeat(44);
    private static final String LINE_DOUBLE = "═".repeat(44);

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
