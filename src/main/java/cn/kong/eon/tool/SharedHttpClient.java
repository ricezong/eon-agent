package cn.kong.eon.tool;

import cn.kong.eon.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 HttpClient 单例。
 * <p>
 * HttpClient 内部维护连接池和线程池，多个工具（WebFetchTool、WebSearchTool、DownloadFileTool）
 * 应共享同一实例，避免重复创建导致连接资源浪费。
 * <p>
 * 配置：
 * - 连接超时：从 {@link AgentConfig} 读取（默认 30 秒）
 * - 重定向：NORMAL（自动跟随）
 * <p>
 * 在 Spring 环境下由 Spring 容器创建为单例 Bean；
 * 会话级 Tool 类通过 {@link #getInstance()} 获取同一实例（委托到 Spring 注入的实例）。
 */
@Component
public final class SharedHttpClient {
    private static final Logger log = LoggerFactory.getLogger(SharedHttpClient.class);

    private static volatile HttpClient instance;

    /**
     * Spring 构造注入。从 {@link AgentConfig} 读取连接超时并创建 HttpClient。
     */
    public SharedHttpClient(AgentConfig config) {
        int timeoutSeconds = config.getTools().getHttpConnectTimeoutSeconds();
        instance = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("SharedHttpClient initialized: connectTimeout={}s", timeoutSeconds);
    }

    /**
     * 获取共享 HttpClient 实例。
     * <p>
     * 在 Spring 环境下由构造函数注入；在非 Spring 环境下（如单元测试）懒初始化默认实例。
     */
    public static HttpClient getInstance() {
        if (instance == null) {
            synchronized (SharedHttpClient.class) {
                if (instance == null) {
                    instance = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(30))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
            }
        }
        return instance;
    }
}
