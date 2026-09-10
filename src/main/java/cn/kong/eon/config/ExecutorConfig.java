package cn.kong.eon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池配置。
 */
@Configuration
public class ExecutorConfig {

    /**
     * SSE 推送线程池。destroyMethod = "shutdown" 确保容器关闭时正确释放。
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
