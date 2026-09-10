package cn.kong.eon.config;

import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 HttpClient 配置。多个工具共享同一实例，避免重复创建导致连接资源浪费。
 */
@Component
public class HttpConfig {

    private final HttpClient httpClient;

    public HttpConfig() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 获取共享 HttpClient 实例。 */
    public HttpClient getClient() {
        return httpClient;
    }
}
