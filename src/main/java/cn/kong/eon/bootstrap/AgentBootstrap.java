package cn.kong.eon.bootstrap;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.agent.capability.*;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.mcp.McpClientManager;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.*;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.tool.builtin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Agent 组装器。
 *
 * 单入口架构：构建 EonAgent + 挂载能力模块。
 * 不区分聊天模式和 Agent 模式，由 PolicyRouter 自动路由。
 */
public class AgentBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    /**
     * 构建 EonAgent。
     */
    public static EonAgent build(AgentConfig config, String sessionId) {
        // 1. 创建 Store 层（所有路径基于 base_dir/{sessionId}/ 隔离）
        Path sessionDir = Path.of(config.getStorage().baseDir, sessionId);
        TodoStore todoStore = new TodoStore();
        InsightsStore insightsStore = new InsightsStore(
                config.getContext().insightsMaxItems,
                config.getContext().insightsMaxChars);
        ArtifactStore artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        JsonlStore jsonlStore = new JsonlStore(sessionDir.resolve("transcript.jsonl"));
        CheckpointStore checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"));

        // 2. 创建 Tool 层（全量注册，LLM 自主选用）
        ToolRegistry toolRegistry = new ToolRegistry(config.getTools().whitelist);
        registerAllTools(toolRegistry);

        // 2.1 连接 MCP 服务
        List<McpClientManager> mcpManagers = connectMcpServers(config, toolRegistry);

        ToolResultRenderer resultRenderer = new ToolResultRenderer(artifactStore);
        ToolContext toolContext = new ToolContext(
                todoStore, artifactStore, insightsStore, jsonlStore, checkpointStore,
                sessionDir.resolve("downloads").toString(),
                sessionDir.resolve("workspace").toString());

        // 3. 加载基础提示词
        String basePrompt = loadPrompt(config.getContext().systemPromptPath);

        // 4. 创建 LLM 层
        LlmClient llmClient = new LlmClient(config);

        // 5. 创建 EonAgent
        EonAgent agent = new EonAgent(config, llmClient, toolRegistry, resultRenderer, jsonlStore, basePrompt, toolContext);

        // 6. 挂载能力模块
        agent.addCapability(new ContextCompactor(config, llmClient));
        agent.addCapability(new BudgetGuard(config));
        agent.addCapability(new NudgeRenderer());
        agent.addCapability(new LoopGuard(config));
        agent.addCapability(new GateKeeperCapability(toolRegistry, true));
        agent.addCapability(new TodoNavigator(todoStore, insightsStore));
        agent.addCapability(new CheckpointManager(config, checkpointStore, todoStore::getAll));

        log.info("EonAgent built with {} capability modules", agent.getCapabilityCount());

        // 注册 JVM 关闭钩子
        if (!mcpManagers.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (McpClientManager mgr : mcpManagers) {
                    mgr.close();
                }
            }));
        }

        return agent;
    }

    /**
     * 注册全部本地工具。
     */
    private static void registerAllTools(ToolRegistry toolRegistry) {
        toolRegistry.register(TodoWriteTool.descriptor());
        toolRegistry.register(TodoReadTool.descriptor());
        toolRegistry.register(WorkingMemoryTool.descriptor());
        toolRegistry.register(FinishTool.descriptor());
        toolRegistry.register(WebSearchTool.descriptor());
        toolRegistry.register(WebReadTool.descriptor());
        toolRegistry.register(DownloadTool.descriptor());
        toolRegistry.register(FileIoTool.descriptor());
        log.info("All local tools registered (7)");
    }

    /**
     * 连接所有已启用的 MCP 服务。
     */
    private static List<McpClientManager> connectMcpServers(AgentConfig config, ToolRegistry toolRegistry) {
        List<McpClientManager> managers = new ArrayList<>();
        List<AgentConfig.McpServerConfig> servers = config.getMcp().getEnabledServers();

        if (servers.isEmpty()) {
            log.info("No MCP servers configured or enabled");
            return managers;
        }

        for (AgentConfig.McpServerConfig server : servers) {
            log.info("Connecting MCP server: {} -> {}", server.key, server.url);
            McpClientManager manager = new McpClientManager(server.key, server.url);
            try {
                manager.connect();
                if (manager.isConnected()) {
                    int registered = toolRegistry.registerMcpTools(manager, server.permission);
                    log.info("MCP server '{}' connected: {} tools registered", server.key, registered);
                    managers.add(manager);
                }
            } catch (Exception e) {
                log.error("Failed to connect MCP server '{}': {}", server.key, e.getMessage(), e);
            }
        }

        log.info("MCP summary: {}/{} servers connected, {} MCP tools total",
                managers.size(), servers.size(), toolRegistry.getMcpToolCount());
        return managers;
    }

    /**
     * 从 classpath 或文件系统加载提示词。
     */
    private static String loadPrompt(String relativePath) {
        try {
            Path promptPath = Path.of(relativePath);
            if (!Files.exists(promptPath)) {
                try (var is = AgentBootstrap.class.getClassLoader().getResourceAsStream(relativePath)) {
                    if (is != null) {
                        return new String(is.readAllBytes());
                    }
                }
                promptPath = Path.of("src/main/resources/" + relativePath);
            }
            return Files.readString(promptPath);
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", relativePath, e);
            return "你是 Eon Agent。请使用工具完成任务。";
        }
    }

    /**
     * 交互式启动。
     */
    public static void main(String[] args) {
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");

        System.out.println("========================================");
        System.out.println("  Eon Agent v2.0");
        System.out.println("  LLM: " + config.getLlm().provider + " / " + config.getLlm().modelName);
        System.out.println("  MCP servers: " + config.getMcp().servers.keySet());
        System.out.println("========================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        System.out.print("请输入问题或任务: ");
        String userInput = scanner.nextLine().trim();

        if (userInput.isEmpty()) {
            System.out.println("输入为空，退出。");
            return;
        }

        String sessionId = "session_" + System.currentTimeMillis();
        SessionState state = SessionState.create(sessionId, userInput);

        EonAgent agent = build(config, sessionId);
        String result = agent.run(state);

        System.out.println();
        System.out.println("========================================");
        System.out.println("  结束");
        System.out.println("========================================");
        System.out.println(result);
    }
}
