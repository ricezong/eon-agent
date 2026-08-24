package cn.kong.eon.session;

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
import cn.kong.eon.tool.SharedHttpClient;
import cn.kong.eon.tool.ToolResultRenderer;
import cn.kong.eon.tool.builtin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 实例工厂。
 * <p>
 * 从 {@link AgentBootstrap} 的 {@code build()} 逻辑提取而来，转为实例方法，
 * 返回 {@link AgentSession}（包含 EonAgent + SessionState + MCP 连接）。
 * <p>
 * 全局共享依赖（{@link AgentConfig}、{@link LlmClient}）由 Spring 注入；
 * 会话级资源（Store、ToolRegistry、ToolContext、Hook）每次创建新实例。
 */
public class AgentBootstrapFactory {
    private static final Logger log = LoggerFactory.getLogger(AgentBootstrapFactory.class);

    private final AgentConfig config;
    private final LlmClient llmClient;

    public AgentBootstrapFactory(AgentConfig config, LlmClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
    }

    /**
     * 创建一个完整的会话。
     *
     * @param sessionId 会话 ID
     * @param userInput 用户首轮输入
     * @return 会话上下文
     */
    public AgentSession createSession(String sessionId, String userInput) {
        // 1. Store 层（按 sessionId 隔离）
        Path sessionDir = Path.of(config.getStorage().baseDir, sessionId);
        TodoStore todoStore = new TodoStore();
        MemoryStore memoryStore = new MemoryStore(Path.of(config.getStorage().baseDir));
        ArtifactStore artifactStore = new ArtifactStore(sessionDir.resolve("artifacts"));
        JsonlStore jsonlStore = new JsonlStore(sessionDir.resolve("transcript.jsonl"));
        CheckpointStore checkpointStore = new CheckpointStore(sessionDir.resolve("checkpoints"));

        // 2. Tool 层
        SharedHttpClient.configure(config.getTools().httpConnectTimeoutSeconds);
        ToolRegistry toolRegistry = new ToolRegistry(config.getTools().whitelist);
        registerAllTools(toolRegistry, config);

        // 2.1 MCP 服务
        List<McpClientManager> mcpManagers = connectMcpServers(config, toolRegistry);

        ToolResultRenderer resultRenderer = new ToolResultRenderer(artifactStore);

        Path workspaceDir = sessionDir.resolve("workspace");
        try {
            Files.createDirectories(workspaceDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建会话工作目录: " + workspaceDir, e);
        }

        // 使用 holder 延迟绑定 InteractionCallback（解决循环依赖）
        cn.kong.eon.tool.InteractionCallbackHolder interactionHolder = new cn.kong.eon.tool.InteractionCallbackHolder();
        ToolContext toolContext = new ToolContext(
                todoStore, artifactStore, memoryStore, jsonlStore, checkpointStore,
                workspaceDir.toString(), interactionHolder);

        // 3. 加载提示词
        String basePrompt = loadPrompt(config.getContext().systemPromptPath);

        // 4. 共享 LoopDetector
        LoopDetector loopDetector = new LoopDetector(
                config.getLoopDetect().repeatWarn,
                config.getLoopDetect().repeatStop,
                config.getLoopDetect().noProgressSteps,
                config.getLoopDetect().failureWarn,
                config.getLoopDetect().failureStop);

        // 5. 创建 EonAgent
        EonAgent agent = new EonAgent(config, llmClient, toolRegistry, resultRenderer,
                jsonlStore, basePrompt, toolContext, loopDetector);

        // 6. 挂载 Hook
        int stopGraceSteps = config.getLoopDetect().stopGraceSteps;
        agent.addHook(new BudgetHook(config));
        agent.addHook(new TodoNavigatorHook(todoStore));
        agent.addHook(new ContextCompactHook(config, llmClient,
                sessionDir.resolve("transcript.jsonl").toString()));
        agent.addHook(new LoopDetectHook(loopDetector, stopGraceSteps));
        agent.addHook(new GateHook(toolRegistry, stopGraceSteps));
        agent.addHook(new FailureBreakerHook(loopDetector));
        agent.addHook(new CheckpointHook(config, checkpointStore, todoStore));

        log.info("EonAgent built for session={} with {} hooks", sessionId, agent.getHookCount());

        // 7. 尝试从 checkpoint 恢复
        if (config.isCheckpointEnabled()) {
            Checkpoint cp = checkpointStore.loadLatest(sessionId);
            if (cp != null) {
                log.info("Checkpoint recovered: turn={}, session={}", cp.getTurnCount(), sessionId);
                if (cp.getTodoSnapshot() != null && !cp.getTodoSnapshot().isEmpty()) {
                    todoStore.replaceAll(cp.getTodoSnapshot(), cp.getTurnCount());
                }
            }
        }

        // 8. 创建 SessionState
        SessionState state = SessionState.create(sessionId, userInput);

        AgentSession session = new AgentSession(sessionId, agent, state, mcpManagers);

        // 9. 绑定 InteractionCallback（延迟绑定解决循环依赖）
        interactionHolder.setDelegate(session.getInteractionCallback());

        return session;
    }

    private void registerAllTools(ToolRegistry toolRegistry, AgentConfig config) {
        toolRegistry.register(ReadFileTool.descriptor());
        toolRegistry.register(WriteFileTool.descriptor());
        toolRegistry.register(DeleteFileTool.descriptor());
        toolRegistry.register(ListDirTool.descriptor());
        long maxFileSizeBytes = config.getTools().downloadMaxFileSizeMb * 1024 * 1024;
        toolRegistry.register(DownloadFileTool.descriptor(maxFileSizeBytes));
        int grepMaxFileSizeBytes = config.getTools().grepMaxFileSizeMb * 1024 * 1024;
        toolRegistry.register(GrepTool.descriptor(config.getTools().grepMaxMatchLines, grepMaxFileSizeBytes, config.getTools().grepMaxOutputChars));
        toolRegistry.register(TodoWriteTool.descriptor());
        toolRegistry.register(AskQuestionTool.descriptor());
        toolRegistry.register(UpdateMemoryTool.descriptor());
        toolRegistry.register(WebFetchTool.descriptor(
                config.getTools().webFetchMaxContentLength,
                config.getTools().webFetchCacheTtlMinutes,
                config.getTools().webFetchCacheMaxEntries));
        toolRegistry.register(WebSearchTool.descriptor(config.getWebSearch().apiKey));
        log.info("Local tools registered ({})", toolRegistry.getAll().size());
    }

    private List<McpClientManager> connectMcpServers(AgentConfig config, ToolRegistry toolRegistry) {
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

    private String loadPrompt(String relativePath) {
        try {
            try (var is = AgentBootstrapFactory.class.getClassLoader().getResourceAsStream(relativePath)) {
                if (is != null) {
                    return new String(is.readAllBytes());
                }
            }
            Path promptPath = Path.of(relativePath);
            if (!Files.exists(promptPath)) {
                promptPath = Path.of("src/main/resources/" + relativePath);
            }
            return Files.readString(promptPath);
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", relativePath, e);
            return "你是 Eon Agent。请使用工具完成任务。";
        }
    }
}
