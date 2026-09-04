package cn.kong.eon.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置加载行为测试。重点是 MCP 服务名（map key）是否真的注入到 McpServerConfig.key，
 * 否则 getKey() 恒为 null，服务名会退化成 "default"。
 */
class AgentConfigTest {

    private static AgentConfig load(String yaml) {
        return AgentConfig.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void mcpServerKeyIsInjectedFromMapKey() {
        AgentConfig cfg = load("""
                mcp:
                  servers:
                    reader-mcp-server:
                      url: "http://example.com/mcp"
                      enabled: true
                      permission: "READONLY"
                """);

        var server = cfg.getMcp().getServers().get("reader-mcp-server");

        assertThat(server).isNotNull();
        assertThat(server.getKey()).isEqualTo("reader-mcp-server");
        assertThat(server.getUrl()).isEqualTo("http://example.com/mcp");
    }

    @Test
    void multipleMcpServersKeepTheirOwnKeys() {
        AgentConfig cfg = load("""
                mcp:
                  servers:
                    alpha:
                      url: "http://a/mcp"
                    beta:
                      url: "http://b/mcp"
                """);

        assertThat(cfg.getMcp().getServers().get("alpha").getKey()).isEqualTo("alpha");
        assertThat(cfg.getMcp().getServers().get("beta").getKey()).isEqualTo("beta");
    }

    @Test
    void webSearchDefaultsAreLoaded() {
        AgentConfig cfg = load("""
                web_search:
                  api_key: "k"
                  search_source: "baidu_search_v2"
                  top_k: 7
                  recency_filter: "pw"
                """);

        assertThat(cfg.getWebSearch().getSearchSource()).isEqualTo("baidu_search_v2");
        assertThat(cfg.getWebSearch().getTopK()).isEqualTo(7);
        assertThat(cfg.getWebSearch().getRecencyFilter()).isEqualTo("pw");
    }

    @Test
    void whitelistIsLoaded() {
        AgentConfig cfg = load("""
                tools:
                  whitelist:
                    - "read_file"
                    - "write"
                """);

        // Jackson 反序列化 Set 不保证元素顺序，集合语义只断言内容
        assertThat(cfg.getTools().getWhitelist())
                .containsExactlyInAnyOrder("read_file", "write");
    }
}
