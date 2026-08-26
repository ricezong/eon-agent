package cn.kong.eon.tool.mcp;

import cn.kong.eon.tool.ToolOutcome;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * MCP 客户端管理器。负责连接 MCP 服务、获取工具列表、执行工具调用。
 */
public class McpClientManager {
    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final String serverKey;
    private final String serverUrl;
    private McpClient mcpClient;

    public McpClientManager(String serverKey, String serverUrl) {
        this.serverKey = serverKey;
        this.serverUrl = serverUrl;
    }

    /**
     * 连接 MCP 服务。
     */
    public void connect() {
        log.info("连接 MCP 服务: key={}, url={}", serverKey, serverUrl);
        try {
            McpTransport transport = StreamableHttpMcpTransport.builder()
                    .url(serverUrl)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            mcpClient = DefaultMcpClient.builder()
                    .key(serverKey)
                    .transport(transport)
                    .build();
            log.info("MCP 客户端已连接: {}", serverKey);
        } catch (Exception e) {
            log.error("连接 MCP 服务 '{}' 失败: {}", serverKey, e.getMessage(), e);
            throw new RuntimeException("MCP 连接失败: " + serverKey, e);
        }
    }

    /**
     * 获取 MCP 服务提供的工具列表。
     */
    public List<ToolSpecification> listTools() {
        if (mcpClient == null) {
            log.warn("MCP 客户端未连接，无法获取工具列表");
            return Collections.emptyList();
        }
        try {
            List<ToolSpecification> tools = mcpClient.listTools();
            log.info("MCP 服务 '{}' 提供 {} 个工具", serverKey, tools.size());
            for (ToolSpecification t : tools) {
                log.info("  - {}: {}", t.name(), t.description() != null ? t.description() : "");
            }
            return tools;
        } catch (Exception e) {
            log.error("从 '{}' 获取 MCP 工具列表失败: {}", serverKey, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 执行 MCP 工具调用。
     */
    public ToolOutcome executeTool(String toolName, String arguments) {
        if (mcpClient == null) {
            return ToolOutcome.failure("MCP 客户端未连接");
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(arguments != null ? arguments : "{}")
                    .build();
            ToolExecutionResult result = mcpClient.executeTool(request);
            String resultText = result != null ? result.resultText() : "";
            log.debug("MCP 工具 '{}' 执行完成，结果 {} 字符", toolName, resultText != null ? resultText.length() : 0);
            return ToolOutcome.success(resultText != null ? resultText : "");
        } catch (Exception e) {
            log.error("MCP 工具执行失败: {} - {}", toolName, e.getMessage(), e);
            return ToolOutcome.failure("MCP 工具执行失败: " + e.getMessage());
        }
    }

    public void close() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
                log.info("MCP 客户端已关闭: {}", serverKey);
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端失败: {}", e.getMessage());
            }
        }
    }

    public String getServerKey() {
        return serverKey;
    }
}
