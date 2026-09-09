package cn.kong.eon;

import cn.kong.eon.agent.context.ContentTrimmer;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.session.SessionContext;
import cn.kong.eon.tool.mcp.McpClientManager;
import cn.kong.eon.store.SessionRegistry;
import cn.kong.eon.store.SessionRegistry.SessionSummary;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.config.HttpConfig;
import cn.kong.eon.tool.CliInteractionCallback;
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

/**
 * Agent 启动类。应用级组件（配置、LLM、工具、MCP、记忆）只初始化一次；
 * 会话级组件委托给 {@link SessionContext}，切换会话时只重建会话上下文。
 */
public class EonApplication {

    private static final Logger log = LoggerFactory.getLogger(EonApplication.class);

    private static final String CONFIG_PATH = "config/agent.yaml";
    private static final String DEFAULT_WORKDIR = ".";
    private static final String SELECTOR_LAST = "last";
    private static final int DEFAULT_HISTORY_LINES = 20;

    // ── 应用级组件（构造一次，不随会话切换重建）
    private final AgentConfig config;
    private final ContentTrimmer compressor;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final HttpConfig httpConfig;
    private final CliInteractionCallback cliInteractionCallback;
    private final String workDir;
    private final String systemPrompt;
    private final SessionRegistry sessionRegistry;

    /** MCP 客户端列表（应用级，切换会话不重连）。 */
    private final java.util.List<McpClientManager> mcpClients = new java.util.ArrayList<>();

    // ── 会话级组件
    private SessionContext session;

    public EonApplication() {
        this(DEFAULT_WORKDIR);
    }

    public EonApplication(String workDir) {
        this(workDir, null);
    }

    public EonApplication(String workDir, String resumeSelector) {
        this(workDir, resumeSelector, null);
    }

    /** 切换会话时复用同一个 Scanner。 */
    EonApplication(String workDir, String resumeSelector, java.util.Scanner sharedScanner) {
        this.workDir = workDir != null ? workDir : DEFAULT_WORKDIR;
        this.compressor = new ContentTrimmer();

        log.info("从 classpath 加载配置: {}", CONFIG_PATH);
        this.config = AgentConfig.loadFromClasspath(CONFIG_PATH);

        this.systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath());
        log.info("系统提示词已加载: {} 字符", systemPrompt.length());

        this.httpConfig = new HttpConfig();
        this.llmClient = new LlmClient(config);

        Path sessionBaseDir = resolveSessionBaseDir();
        this.sessionRegistry = new SessionRegistry(sessionBaseDir);
        this.memoryStore = new MemoryStore(sessionBaseDir);

        this.toolRegistry = createToolRegistry();
        connectMcpServers();

        this.cliInteractionCallback = sharedScanner != null
                ? new CliInteractionCallback(sharedScanner)
                : new CliInteractionCallback(new java.util.Scanner(System.in, StandardCharsets.UTF_8));

        // 恢复会话时立即初始化
        if (resumeSelector != null && !resumeSelector.isBlank()) {
            SessionSummary resumed = resolveResumed(resumeSelector);
            this.session = new SessionContext(config, llmClient, toolRegistry, memoryStore,
                    compressor, systemPrompt, cliInteractionCallback, resumed);
        }

        log.info("EonApplication 就绪{}: {} 个工具",
                session != null ? "（会话已恢复）" : "（等待用户输入后创建会话）",
                toolRegistry.getAllToolNames().size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话管理
    // ═══════════════════════════════════════════════════════════════════

    /** 运行一轮对话。首次调用时自动初始化会话。 */
    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }
        if (session == null) {
            session = new SessionContext(config, llmClient, toolRegistry, memoryStore,
                    compressor, systemPrompt, cliInteractionCallback, null);
        }
        return session.run(userInput);
    }

    public boolean isSessionInitialized() {
        return session != null;
    }

    /**
     * 切换会话：关闭旧会话上下文，创建新会话上下文。
     * 应用级组件（LLM、工具、MCP）不重建。
     */
    public void switchSession(String resumeSelector) {
        if (session != null) {
            session.close();
            session = null;
        }
        SessionSummary resumed = resolveResumed(resumeSelector);
        this.session = new SessionContext(config, llmClient, toolRegistry, memoryStore,
                compressor, systemPrompt, cliInteractionCallback, resumed);
        log.info("EonApplication 会话已切换: {}", session.getSessionId());
    }

    /**
     * 新建会话：关闭旧会话上下文，创建空会话。
     */
    public void newSession() {
        if (session != null) {
            session.close();
            session = null;
        }
        this.session = new SessionContext(config, llmClient, toolRegistry, memoryStore,
                compressor, systemPrompt, cliInteractionCallback, null);
        log.info("EonApplication 新会话已创建: {}", session.getSessionId());
    }

    /** 关闭应用，释放所有资源。 */
    public void shutdown() {
        log.info("正在关闭 EonApplication...");
        if (session != null) {
            session.close();
        }
        toolRegistry.closeAll();
        for (McpClientManager mcp : mcpClients) {
            mcp.close();
        }
        log.info("EonApplication 已关闭。");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getter（供 CLI 使用）
    // ═══════════════════════════════════════════════════════════════════

    public SessionContext getSession() { return session; }
    public SessionRegistry getSessionRegistry() { return sessionRegistry; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

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
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CLI
    // ═══════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        String workDir = System.getProperty("eon.workdir", DEFAULT_WORKDIR);
        log.info("启动 Eon Agent, 工作目录={}", workDir);

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
                try {
                    app.switchSession(selector);
                } catch (Exception e) {
                    log.error("切换会话失败: {}", selector, e);
                    System.out.println("切换会话失败: " + e.getMessage() + "，已回退到新会话");
                    app.newSession();
                }
                printWelcome(app);
                continue;
            }
            if (input.equalsIgnoreCase("/new")) {
                app.newSession();
                printWelcome(app);
                continue;
            }
            if (input.toLowerCase().startsWith("/history")) {
                if (!app.isSessionInitialized()) {
                    System.out.println("当前无活跃会话，发送消息后将自动创建会话。");
                    continue;
                }
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
                app.getSessionRegistry().delete(selector);
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

    private static String resolveSelector(EonApplication app, String token) {
        if (!token.matches("\\d+")) return token;
        var sessions = app.getSessionRegistry().list();
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

    private static void printSessions(EonApplication app) {
        var sessions = app.getSessionRegistry().list();
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

    private static void printHistory(EonApplication app, int n) {
        var blocks = app.getSession().getJsonlStore().window().blocks();
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
        if (app.isSessionInitialized()) {
            var session = app.getSession();
            System.out.println(" 会话: " + session.getSessionId());
            if (session.getResumedSession() != null) {
                var s = session.getResumedSession();
                System.out.println(" 恢复: " + s.title() + " — " + relativeTime(s.lastActivityAt())
                        + " · " + s.messageCount() + " 条 · " + (s.hasSnapshot() ? "有快照" : "无快照"));
            }
        } else {
            System.out.println(" 会话: 尚未创建（发送消息后将自动创建）");
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
        System.out.println("           不带参数启动则等待用户输入后创建新会话");
        System.out.println();
    }

    private static void printTools(EonApplication app) {
        var names = app.getToolRegistry().getAllToolNames();
        System.out.println();
        System.out.println(" 可用工具列表（" + names.size() + "）");
        System.out.println(" " + LINE_BOLD);
        System.out.println();

        for (String name : names) {
            var perm = app.getToolRegistry().getPermission(name);
            String permTag = perm != null ? formatPermission(perm) : "?";
            boolean isMcp = app.getToolRegistry().isMcpTool(name);
            var desc = app.getToolRegistry().get(name);
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
