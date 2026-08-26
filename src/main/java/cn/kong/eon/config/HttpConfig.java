package cn.kong.eon.config;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 HttpClient 配置类。
 * <p>
 * HttpClient 内部维护连接池和线程池，多个工具（WebFetchTool、WebSearchTool、DownloadFileTool）
 * 应共享同一实例，避免重复创建导致连接资源浪费。
 * <p>
 * 通过构造函数注入连接超时，由 {@code EonApplication} 创建单实例后分发给各工具。
 * 重定向策略固定为 NORMAL（自动跟随）。
 */
public final class HttpConfig {

    private final HttpClient httpClient;

    /**
     * @param connectTimeoutSeconds 连接超时（秒）
     */
    public HttpConfig(int connectTimeoutSeconds) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 获取底层 HttpClient 实例。 */
    public HttpClient getClient() {
        return httpClient;
    }
}
