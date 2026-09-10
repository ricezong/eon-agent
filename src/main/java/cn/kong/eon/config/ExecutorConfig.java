package cn.kong.eon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池配置。通过 Spring Bean 管理线程池生命周期，
 * 避免手动创建导致资源泄漏。
 */
@Configuration
public class ExecutorConfig {

    /**
     * SSE 异步推送线程池。用于 AgentController 的 SSE 流式响应。
     * destroyMethod = "shutdown" 确保容器关闭时线程池被正确关闭。
     */
    @Bean(name = "sseExecutor", destroyMethod = "shutdown")
    public ExecutorService sseExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-push");
            t.setDaemon(true);
            return t;
        });
    }
}
