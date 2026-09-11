package cn.kong.eon.config;

import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.context.block.CompressionLevel;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.context.policy.CompressionSettings;
import cn.kong.eon.context.summary.ContextSummarizer;
import cn.kong.eon.llm.LlmService;
import cn.kong.eon.tool.ToolService;
import cn.kong.eon.tool.builtin.*;
import cn.kong.eon.tool.mcp.McpServerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用级组件装配：集中构造工具注册表、系统提示词、token 估算器等进程级单例。
 */
@Configuration
public class AgentBeans {

    private static final Logger log = LoggerFactory.getLogger(AgentBeans.class);

    /** 已连接的 MCP 客户端，容器关闭时统一释放。 */
    private final List<McpServerClient> mcpClients = new ArrayList<>();

    /**
     * 工具注册表：注册内置工具并连接 MCP 注册远程工具。
     */
    @Bean(destroyMethod = "closeAll")
    public ToolService toolService(AgentConfig config,
                                   ObjectMapper objectMapper,
                                   HttpConfig httpConfig) {
        ToolService registry = new ToolService(config.getTools().getWhitelist(), objectMapper);

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

        connectMcpServers(config, registry);

        log.info("ToolService 就绪: {} 个工具", registry.getAllToolNames().size());
        return registry;
    }

    /** 压缩策略（应用级单例）。 */
    @Bean
    public CompressionPolicy compressionPolicy(AgentConfig config,
                                               ContentCompressor compressor,
                                               LlmService llmService) {
        var ctxCfg = config.getContext();
        var comp = ctxCfg.getCompression();
        ContextSummarizer summarizer = new ContextSummarizer(
                llmService, ctxCfg.getSummarizeMaxInputChars(), ctxCfg.getSummarizeMaxOutputChars());
        CompressionLevel turnLevel = parseTurnLevel(comp.getTurnLevel());
        log.info("压缩策略已装配: 水位 {}/{}/{} | 轮数周期 {} 档位 {} | 尾部保护 {} 块 | 参数裁剪阈值 {} 字符",
                comp.getSnipWaterLevel(), comp.getPruneWaterLevel(), comp.getSummarizeWaterLevel(),
                comp.getTurnInterval(), turnLevel,
                comp.getTailGuardBlocks(), comp.getArgsPruneMinChars());
        CompressionSettings settings = new CompressionSettings(
                comp.getSnipWaterLevel(), comp.getPruneWaterLevel(), comp.getSummarizeWaterLevel(),
                comp.getTurnInterval(), turnLevel,
                comp.getTailGuardBlocks(), comp.getArgsPruneMinChars(),
                ctxCfg.getSnipKeepChars());
        return new CompressionPolicy(settings, compressor, summarizer);
    }

    /** 解析轮数兜底档位字符串，非法值回退为 SNIP。 */
    private CompressionLevel parseTurnLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return CompressionLevel.SNIP;
        }
        try {
            return CompressionLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知的压缩档位 '{}'，回退为 SNIP", raw);
            return CompressionLevel.SNIP;
        }
    }

    /** 系统提示词，注入处需 {@code @Qualifier("systemPrompt")}。 */
    @Bean("systemPrompt")
    public String systemPrompt(AgentConfig config, ResourceLoader resourceLoader) {
        String path = config.getContext().getSystemPromptPath();
        try {
            Resource resource = resourceLoader.getResource("classpath:" + path);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String prompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.info("系统提示词已加载: {} 字符", prompt.length());
                    return prompt;
                }
            }
        } catch (IOException e) {
            log.warn("从 classpath 加载系统提示词失败: {}", path, e);
        }
        log.error("系统提示词未找到，使用空提示词");
        return "";
    }

    /** token 估算器。 */
    @Bean
    public TokenCountEstimator tokenCountEstimator() {
        return new OpenAiTokenCountEstimator("gpt-4o");
    }

    @PreDestroy
    public void shutdown() {
        for (McpServerClient mcp : mcpClients) {
            try {
                mcp.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端失败", e);
            }
        }
        mcpClients.clear();
    }

    /** 逐个连接 MCP 服务并注册工具，单个失败只跳过。 */
    private void connectMcpServers(AgentConfig config, ToolService registry) {
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
                int toolCount = registry.registerMcpTools(mcpClient, serverCfg.getPermission());
                log.info("MCP 服务 '{}' 已连接: 注册 {} 个工具", serverKey, toolCount);
                mcpClients.add(mcpClient);
            } catch (Exception e) {
                log.error("连接 MCP 服务 '{}' 失败: {}", serverKey, e.getMessage(), e);
            }
        }
    }
}
