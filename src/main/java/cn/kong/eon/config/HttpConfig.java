package cn.kong.eon.config;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 HttpClient 配置类。多个工具共享同一实例，避免重复创建导致连接资源浪费。
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

    /**
     * 获取底层 HttpClient 实例。
     */
    public HttpClient getClient() {
        return httpClient;
    }
}
