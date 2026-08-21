package cn.kong.eon.bootstrap;

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
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.mcp.McpClientManager;
import cn.kong.eon.model.Checkpoint;
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

/** Agent 组装器：构建 EonAgent + 挂载 Hook + 连接 MCP 服务。 */
public class AgentBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    public static EonAgent build(AgentConfig config, String sessionId) {
        // 1. Store 层（按 sessionId 隔离）
        Path sessionDir = Path.of(config.getStorage().baseDir, sessionId);
        TodoStore todoStore = new TodoStore();
        InsightsStore insightsStore = new InsightsStore(
config.getContext().INSIGHTS_MAX_ITEMS,
config.getContext().INSIGHTS_MAX_CHARS);
        ArtifactStore artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        JsonlStore jsonlStore = new JsonlStore(sessionDir.resolve("transcript.jsonl"));
        CheckpointStore checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"));

        // 2. Tool 层
        ToolRegistry toolRegistry = new ToolRegistry(config.getTools().whitelist);
        registerAllTools(toolRegistry, config);

        // 2.1 MCP 服务
        List<McpClientManager> mcpManagers = connectMcpServers(config, toolRegistry);

        ToolResultRenderer resultRenderer = new ToolResultRenderer(artifactStore);
        ToolContext toolContext = new ToolContext(
                todoStore, artifactStore, insightsStore, jsonlStore, checkpointStore,
                sessionDir.resolve("downloads").toString(),
                sessionDir.resolve("workspace").toString());

        // 3. 加载提示词
        String basePrompt = loadPrompt(config.getContext().systemPromptPath);

        // 4. LLM 层
        LlmClient llmClient = new LlmClient(config);

        // 5. 共享 LoopDetector（LoopDetect 和 FailureBreaker 共用状态）
        LoopDetector loopDetector = new LoopDetector(
                config.getLoopDetect().repeatWarn,
                config.getLoopDetect().repeatStop,
                config.getLoopDetect().noProgressSteps,
                config.getLoopDetect().failureWarn,
                config.getLoopDetect().failureStop);

        // 6. 创建 EonAgent
        EonAgent agent = new EonAgent(config, llmClient, toolRegistry, resultRenderer, jsonlStore, basePrompt, toolContext, loopDetector);

        // 7. 挂载 Hook（按执行阶段分组）
        // PreModel 阶段
        agent.addHook(new BudgetHook(config));                              // order=10
        agent.addHook(new TodoNavigatorHook(todoStore, insightsStore));    // order=20
        agent.addHook(new ContextCompactHook(config, llmClient));          // order=100

        // PostModel 阶段
        agent.addHook(new LoopDetectHook(loopDetector));                   // order=30

        // PreTool 阶段
        agent.addHook(new GateHook(toolRegistry));                   // order=20

        // PostTool 阶段
        agent.addHook(new FailureBreakerHook(loopDetector));               // order=30
        agent.addHook(new CheckpointHook(config, checkpointStore, todoStore, insightsStore)); // order=100

        log.info("EonAgent built with {} hooks", agent.getHookCount());

        // 尝试从 checkpoint 恢复（如果配置启用且存在快照）
        if (config.isCheckpointEnabled()) {
            Checkpoint cp = checkpointStore.loadLatest(sessionId);
            if (cp != null) {
                log.info("Checkpoint recovered: turn={}, session={}", cp.getTurnCount(), sessionId);
                if (cp.getTodoSnapshot() != null && !cp.getTodoSnapshot().isEmpty()) {
                    todoStore.replaceAll(cp.getTodoSnapshot(), cp.getTurnCount());
                }
            }
        }

        // 注册 JVM 关闭钩子
        if (!mcpManagers.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (McpClientManager mgr : mcpManagers) {
                    try {
                        mgr.close();
                        log.info("MCP server closed: {}", mgr.getServerKey());
                    } catch (Exception e) {
                        log.warn("Failed to close MCP server: {}", mgr.getServerKey(), e);
                    }
                }
            }, "MCP-ShutdownHook"));
        }

        return agent;
    }

    /** 注册全部本地工具。 */
    private static void registerAllTools(ToolRegistry toolRegistry, AgentConfig config) {
        toolRegistry.register(TodoWriteTool.descriptor());
        toolRegistry.register(TodoReadTool.descriptor());
        toolRegistry.register(WorkingMemoryTool.descriptor());
        toolRegistry.register(FinishTool.descriptor());
        toolRegistry.register(WebSearchTool.descriptor(config.getWebSearch().apiKey));
        toolRegistry.register(WebReadTool.descriptor());
        toolRegistry.register(DownloadTool.descriptor());
        toolRegistry.register(FileIoTool.descriptor());
        toolRegistry.register(DateTimeTool.descriptor());
        log.info("All local tools registered (9)");
    }

    /** 连接所有已启用的 MCP 服务，注册远程工具。 */
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

    /** 从 classpath 或文件系统加载提示词。 */
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

    /** 交互式启动。 */
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
