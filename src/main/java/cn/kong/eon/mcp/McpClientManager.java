package cn.kong.eon.mcp;

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
 * MCP 工具的 ToolSpecification 直接复用 MCP 服务返回的 schema。
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

    /** 连接 MCP 服务。 */
    public void connect() {
        log.info("Connecting to MCP server: key={}, url={}", serverKey, serverUrl);
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
            log.info("MCP client connected: {}", serverKey);
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}': {}", serverKey, e.getMessage(), e);
            throw new RuntimeException("MCP connection failed: " + serverKey, e);
        }
    }

    /** 获取 MCP 服务提供的工具列表。 */
    public List<ToolSpecification> listTools() {
        if (mcpClient == null) {
            log.warn("MCP client not connected, cannot list tools");
            return Collections.emptyList();
        }
        try {
            List<ToolSpecification> tools = mcpClient.listTools();
            log.info("MCP server '{}' provides {} tools", serverKey, tools.size());
            for (ToolSpecification t : tools) {
                log.info("  - {}: {}", t.name(), t.description() != null ? t.description() : "");
            }
            return tools;
        } catch (Exception e) {
            log.error("Failed to list MCP tools from '{}': {}", serverKey, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** 执行 MCP 工具调用。 */
    public ToolOutcome executeTool(String toolName, String arguments) {
        if (mcpClient == null) {
            return ToolOutcome.failure("MCP client not connected");
        }
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(arguments != null ? arguments : "{}")
                    .build();
            ToolExecutionResult result = mcpClient.executeTool(request);
            String resultText = result != null ? result.resultText() : "";
            log.debug("MCP tool '{}' executed, result {} chars", toolName, resultText != null ? resultText.length() : 0);
            return ToolOutcome.success(resultText != null ? resultText : "");
        } catch (Exception e) {
            log.error("MCP tool execution failed: {} - {}", toolName, e.getMessage(), e);
            return ToolOutcome.failure("MCP tool execution failed: " + e.getMessage());
        }
    }

    public void close() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
                log.info("MCP client closed: {}", serverKey);
            } catch (Exception e) {
                log.warn("Failed to close MCP client: {}", e.getMessage());
            }
        }
    }

    public boolean isConnected() { return mcpClient != null; }
    public String getServerKey() { return serverKey; }
    public String getServerUrl() { return serverUrl; }
    public McpClient getMcpClient() { return mcpClient; }
}
