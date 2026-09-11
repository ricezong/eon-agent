package cn.kong.eon.config;

import cn.kong.eon.runtime.SessionScope;
import cn.kong.eon.runtime.SessionScopeRemovalListener;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 活跃会话上下文缓存。
 * <p>
 * 缓存的是 {@link SessionScope}（会话级上下文，跨多次 run 复用），命中即跳过账本回放。
 * 关键约束：同 session 必须互斥执行（见 {@link cn.kong.eon.runtime.SessionScope#tryAcquire()}），
 * 否则熔断器计数、压缩水位、账本窗口会被两个 run 交错写坏。
 */
@Configuration
public class SessionCacheConfig {

    @Bean
    public Cache<String, SessionScope> sessionCache(AgentConfig config,
                                                    SessionScopeRemovalListener removalListener) {
        AgentConfig.SessionConfig.CacheConfig c = config.getSession().getCache();

        return Caffeine.newBuilder()
                .maximumSize(c.getMaximumSize())
                // 变长过期：RUNNING 状态自动获得长 TTL，保证执行中不被淘汰
                .expireAfter(new SessionExpiry(
                        Duration.ofMinutes(c.getIdleTtlMinutes()),
                        Duration.ofMinutes(c.getRunningTtlMinutes())))
                .removalListener(removalListener)
                // 同步执行淘汰回调，避免"条目已移除但 close() 还没跑"的时序窗口
                .executor(Runnable::run)
                .recordStats()
                .build();
    }
}
