package cn.kong.eon.tool;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 共享 HttpClient 单例。
 * <p>
 * HttpClient 内部维护连接池和线程池，多个工具（WebFetchTool、WebSearchTool、DownloadFileTool）
 * 应共享同一实例，避免重复创建导致连接资源浪费。
 * <p>
 * 配置：
 * - 连接超时：可配置（默认 30 秒），通过 {@link #configure(int)} 在启动期设置
 * - 重定向：NORMAL（自动跟随）
 * <p>
 * 用法：AgentBootstrapFactory 启动时调用 {@link #configure(int)} 设置超时，
 * 之后各工具通过 {@link #getInstance()} 获取共享实例。
 */
public final class SharedHttpClient {

    private static volatile Duration connectTimeout = Duration.ofSeconds(30);
    private static volatile HttpClient instance;

    private SharedHttpClient() {
    }

    /**
     * 配置连接超时。必须在 {@link #getInstance()} 首次调用前执行，否则使用默认值 30 秒。
     *
     * @param connectTimeoutSeconds 连接超时（秒）
     */
    public static void configure(int connectTimeoutSeconds) {
        connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
    }

    /** 获取共享 HttpClient 实例。首次调用时懒初始化。 */
    public static HttpClient getInstance() {
        if (instance == null) {
            synchronized (SharedHttpClient.class) {
                if (instance == null) {
                    instance = HttpClient.newBuilder()
                            .connectTimeout(connectTimeout)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
            }
        }
        return instance;
    }
}
