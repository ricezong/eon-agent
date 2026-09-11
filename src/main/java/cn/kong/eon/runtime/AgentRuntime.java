package cn.kong.eon.runtime;

import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.tool.mcp.McpServerClient;
import cn.kong.eon.store.index.SessionIndexStore;
import cn.kong.eon.store.index.SessionIndexStore.SessionSummary;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.config.HttpConfig;
import cn.kong.eon.tool.ToolService;
import cn.kong.eon.tool.builtin.*;
import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.dto.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import cn.kong.eon.event.AgentEventListener;

/**
 * 应用级容器。管理 LlmClient、ToolRegistry、MCP 连接等重资源，构造一次不随会话切换重建。
 * 会话级组件委托给 {@link AgentSession}，按 sessionId 创建/恢复，运行结束后释放。
 */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    // ── 应用级组件
    private final AgentConfig config;
    private final ContentCompressor compressor;
    private final LlmClient llmClient;
    private final ToolService toolService;
    private final MemoryStore memoryStore;
    private final HttpConfig httpConfig;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final SessionIndexStore sessionIndexStore;

    /** MCP 客户端列表 */
    private final List<McpServerClient> mcpClients = new ArrayList<>();

    /** 运行中的会话上下文（sessionId → SessionContext），用于 interrupt */
    private final ConcurrentHashMap<String, AgentSession> activeSessions = new ConcurrentHashMap<>();

    public AgentRuntime(AgentConfig config,
                        ObjectMapper objectMapper,
                        HttpConfig httpConfig,
                        ContentCompressor contentCompressor,
                        LlmClient llmClient,
                        SessionIndexStore sessionIndexStore,
                        MemoryStore memoryStore,
                        ResourceLoader resourceLoader) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpConfig = httpConfig;
        this.compressor = contentCompressor;
        this.llmClient = llmClient;
        this.sessionIndexStore = sessionIndexStore;
        this.memoryStore = memoryStore;

        log.info("AgentBootstrap 初始化: llm.provider={}, model={}, storage.baseDir={}",
                config.getLlm().getProvider(), config.getLlm().getModelName(),
                config.getStorage().getBaseDir());

        this.systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath(), resourceLoader);
        log.info("系统提示词已加载: {} 字符", systemPrompt.length());

        this.toolService = createToolRegistry();
        connectMcpServers();

        log.info("AgentBootstrap 就绪: {} 个工具", toolService.getAllToolNames().size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对话
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 运行一轮对话。
     * sessionId 为空时新建会话（写入索引），非空时从索引恢复（回放 transcript）。
     */
    public RunResult run(ChatRequest request, List<AgentEventListener> externalListeners) {
        String sessionId = request.sessionId();
        String userId = request.userId() != null ? request.userId() : "default";
        boolean isNew = sessionId == null || sessionId.isBlank();

        SessionSummary resumed = null;
        if (!isNew) {
            Optional<SessionSummary> found = sessionIndexStore.find(userId, sessionId);
            if (found.isEmpty()) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            resumed = found.get();
        }

        AgentSession session = createSession(resumed, externalListeners);
        String actualSessionId = session.getSessionId();
        activeSessions.put(actualSessionId, session);

        if (isNew) {
            sessionIndexStore.insert(actualSessionId, userId, deriveTitle(request.message()));
        }

        try {
            String output = session.run(request.message());
            return new RunResult(output, actualSessionId);
        } finally {
            activeSessions.remove(actualSessionId);
            session.close();
        }
    }

    /** 中断指定会话。 */
    public boolean interrupt(String sessionId) {
        AgentSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.getSessionState().requestInterrupt();
            return true;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭 AgentBootstrap...");
        for (AgentSession session : activeSessions.values()) {
            session.close();
        }
        activeSessions.clear();
        toolService.closeAll();
        for (McpServerClient mcp : mcpClients) {
            mcp.close();
        }
        log.info("AgentBootstrap 已关闭。");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getter
    // ═══════════════════════════════════════════════════════════════════

    public SessionIndexStore getSessionRegistry() { return sessionIndexStore; }

    /** 获取指定会话的账本路径（不需要会话已加载）。 */
    public Path getTranscriptPath(String sessionId) {
        return Path.of(config.getStorage().getBaseDir())
                .toAbsolutePath().normalize()
                .resolve(sessionId)
                .resolve("transcript.jsonl");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    /** 从用户输入派生会话标题（前 10 字符）。 */
    private static String deriveTitle(String userInput) {
        if (userInput == null) return "新会话";
        return userInput.length() <= 10 ? userInput : userInput.substring(0, 10);
    }

    /** 创建会话上下文，注入应用级依赖。 */
    private AgentSession createSession(SessionSummary resumedSession, List<AgentEventListener> externalListeners) {
        return new AgentSession(config, llmClient, toolService, memoryStore,
                compressor, systemPrompt, resumedSession, externalListeners, objectMapper);
    }

    private String loadSystemPrompt(String path, ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:" + path);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            log.warn("从 classpath 加载系统提示词失败: {}", path, e);
        }
        log.error("系统提示词未找到，使用空提示词");
        return "";
    }

    private ToolService createToolRegistry() {
        ToolService registry = new ToolService(
                config.getTools().getWhitelist(), objectMapper);

        registry.register(ReadFileTool.descriptor());
        registry.register(WriteFileTool.descriptor());
        registry.register(ListDirTool.descriptor());
        registry.register(DownloadFileTool.descriptor(
                config.getTools().getDownload().getMaxFileSizeMb() * 1024 * 1024,
                httpConfig.getClient()));
        registry.register(TodoWriteTool.descriptor(objectMapper));
        registry.register(UpdateMemoryTool.descriptor());

        var searchCfg = config.getWebSearch();
        String searchApiKey = searchCfg.getApiKey();
        if (searchApiKey != null && !searchApiKey.isBlank()) {
            registry.register(WebSearchTool.descriptor(
                    searchApiKey,
                    searchCfg.getSearchSource(),
                    searchCfg.getTopK(),
                    searchCfg.getRecencyFilter(),
                    httpConfig.getClient(),
                    objectMapper));
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
                McpServerClient mcpClient = new McpServerClient(serverKey, url);
                mcpClient.connect();
                int toolCount = toolService.registerMcpTools(mcpClient, serverCfg.getPermission());
                log.info("MCP 服务 '{}' 已连接: 注册 {} 个工具", serverKey, toolCount);
                mcpClients.add(mcpClient);
            } catch (Exception e) {
                log.error("连接 MCP 服务 '{}' 失败: {}", serverKey, e.getMessage(), e);
            }
        }
    }
}
