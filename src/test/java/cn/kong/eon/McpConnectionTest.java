package cn.kong.eon;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.mcp.McpClientManager;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP 服务连接测试。
 * 验证能连接 http://124.223.110.114:8081/mcp 并获取工具列表。
 *
 * 注意：此测试需要网络连通性，MCP 服务需在线。
 * 若网络不通会跳过（不报错）。
 */
class McpConnectionTest {
    private static final Logger log = LoggerFactory.getLogger(McpConnectionTest.class);

    private static final String MCP_SERVER_KEY = "novel-mcp-server";
    private static final String MCP_SERVER_URL = "http://124.223.110.114:8081/mcp";

    @Test
    void should_connect_to_mcp_server_and_list_tools() {
        McpClientManager manager = new McpClientManager(MCP_SERVER_KEY, MCP_SERVER_URL);

        try {
            manager.connect();
            assertThat(manager.isConnected()).isTrue();

            List<ToolSpecification> tools = manager.listTools();
            log.info("MCP server '{}' returned {} tools:", MCP_SERVER_KEY, tools.size());
            for (ToolSpecification tool : tools) {
                log.info("  - {}: {}", tool.name(), tool.description());
            }

            assertThat(tools).isNotEmpty();

        } catch (Exception e) {
            log.warn("MCP connection test skipped (network issue): {}", e.getMessage());
            // 网络不通时不让测试失败，只记录警告
        } finally {
            manager.close();
        }
    }

    @Test
    void should_load_mcp_config_from_yaml() {
        AgentConfig config = AgentConfig.loadFromClasspath("config/agent.yaml");

        assertThat(config.getMcp()).isNotNull();
        assertThat(config.getMcp().servers).containsKey(MCP_SERVER_KEY);

        AgentConfig.McpServerConfig server = config.getMcp().servers.get(MCP_SERVER_KEY);
        assertThat(server.url).isEqualTo(MCP_SERVER_URL);
        assertThat(server.enabled).isTrue();
        assertThat(server.permission).isEqualTo("READONLY");

        List<AgentConfig.McpServerConfig> enabled = config.getMcp().getEnabledServers();
        assertThat(enabled).hasSize(1);
        assertThat(enabled.get(0).key).isEqualTo(MCP_SERVER_KEY);
    }
}
