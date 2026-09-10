package cn.kong.eon.app;

import cn.kong.eon.agent.context.ContentTrimmer;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.session.SessionContext;
import cn.kong.eon.tool.mcp.McpClientManager;
import cn.kong.eon.store.SessionRegistry;
import cn.kong.eon.store.SessionRegistry.SessionSummary;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.config.HttpConfig;
import cn.kong.eon.tool.ToolRegistry;
import cn.kong.eon.tool.builtin.*;
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
import java.util.ArrayList;
import java.util.List;
import cn.kong.eon.agent.event.TurnListener;

/**
 * 应用级容器。管理 LlmClient、ToolRegistry、MCP 连接等重资源，构造一次不随会话切换重建。
 * 会话级组件委托给 {@link SessionContext}。
 */
@Component
public class AgentBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentBootstrap.class);

    // ── 应用级组件
    private final AgentConfig config;
    private final ContentTrimmer compressor;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final HttpConfig httpConfig;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;
    private final SessionRegistry sessionRegistry;

    /** MCP 客户端列表 */
    private final List<McpClientManager> mcpClients = new ArrayList<>();

    // ── 会话级组件
    private volatile SessionContext session;

    public AgentBootstrap(AgentConfig config,
                          ObjectMapper objectMapper,
                          HttpConfig httpConfig,
                          ContentTrimmer contentTrimmer,
                          LlmClient llmClient,
                          SessionRegistry sessionRegistry,
                          MemoryStore memoryStore,
                          ResourceLoader resourceLoader) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpConfig = httpConfig;
        this.compressor = contentTrimmer;
        this.llmClient = llmClient;
        this.sessionRegistry = sessionRegistry;
        this.memoryStore = memoryStore;

        log.info("AgentBootstrap 初始化: llm.provider={}, model={}, storage.baseDir={}",
                config.getLlm().getProvider(), config.getLlm().getModelName(),
                config.getStorage().getBaseDir());

        this.systemPrompt = loadSystemPrompt(config.getContext().getSystemPromptPath(), resourceLoader);
        log.info("系统提示词已加载: {} 字符", systemPrompt.length());

        this.toolRegistry = createToolRegistry();
        connectMcpServers();

        log.info("AgentBootstrap 就绪: {} 个工具", toolRegistry.getAllToolNames().size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话管理
    // ═══════════════════════════════════════════════════════════════════

    /** 运行一轮对话，首次调用时自动初始化会话。 */
    public synchronized String run(String userInput) {
        return run(userInput, List.of());
    }

    /** 运行一轮对话，带外部 TurnListener 用于 SSE 推送。 */
    public synchronized String run(String userInput, List<TurnListener> externalListeners) {
        if (userInput == null || userInput.isBlank()) {
            return "输入不能为空。";
        }
        if (session == null) {
            session = createSession(null, externalListeners);
        } else {
            // 动态注册外部 listener
            for (TurnListener l : externalListeners) {
                session.addTurnListener(l);
            }
        }
        try {
            return session.run(userInput);
        } finally {
            // 运行结束后移除外部 listener（SSE 连接已关闭）
            for (TurnListener l : externalListeners) {
                session.removeTurnListener(l);
            }
        }
    }

    public synchronized boolean isSessionInitialized() {
        return session != null;
    }

    /** 切换会话。 */
    public synchronized void switchSession(String resumeSelector) {
        if (session != null) {
            session.close();
            session = null;
        }
        SessionSummary resumed = resolveResumed(resumeSelector);
        this.session = createSession(resumed, List.of());
        log.info("AgentBootstrap 会话已切换: {}", session.getSessionId());
    }

    /** 新建会话。 */
    public synchronized void newSession() {
        if (session != null) {
            session.close();
            session = null;
        }
        this.session = createSession(null, List.of());
        log.info("AgentBootstrap 新会话已创建: {}", session.getSessionId());
    }

    @PreDestroy
    public synchronized void shutdown() {
        log.info("正在关闭 AgentBootstrap...");
        if (session != null) {
            session.close();
        }
        toolRegistry.closeAll();
        for (McpClientManager mcp : mcpClients) {
            mcp.close();
        }
        log.info("AgentBootstrap 已关闭。");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Getter
    // ═══════════════════════════════════════════════════════════════════

    public SessionContext getSession() { return session; }
    public SessionRegistry getSessionRegistry() { return sessionRegistry; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public AgentConfig getConfig() { return config; }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    /** 创建会话上下文，注入应用级依赖。 */
    private SessionContext createSession(SessionSummary resumedSession, List<TurnListener> externalListeners) {
        return new SessionContext(config, llmClient, toolRegistry, memoryStore,
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

    public SessionSummary resolveResumed(String selector) {
        if (selector == null || selector.isBlank()) return null;
        var found = "last".equalsIgnoreCase(selector)
                ? sessionRegistry.last()
                : sessionRegistry.find(selector);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("未找到会话: " + selector);
        }
        return found.get();
    }

    private ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry(
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
}
