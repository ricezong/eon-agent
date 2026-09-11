package cn.kong.eon.config;

import cn.kong.eon.runtime.SessionScope;
import cn.kong.eon.runtime.SessionScopeRemovalListener;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 活跃会话上下文缓存。缓存 SessionScope，命中即跳过账本回放。
 * 同 session 必须互斥执行（见 SessionScope#tryAcquire()）。
 */
@Configuration
public class SessionCacheConfig {

    @Bean
    public Cache<String, SessionScope> sessionCache(AgentConfig config,
                                                    SessionScopeRemovalListener removalListener) {
        AgentConfig.SessionConfig.CacheConfig c = config.getSession().getCache();

        return Caffeine.newBuilder()
                .maximumSize(c.getMaximumSize())
                // 变长过期：RUNNING 状态自动获得长 TTL
                .expireAfter(new SessionExpiry(
                        Duration.ofMinutes(c.getIdleTtlMinutes()),
                        Duration.ofMinutes(c.getRunningTtlMinutes())))
                .removalListener(removalListener)
                // 同步执行淘汰回调，避免 close() 延迟
                .executor(Runnable::run)
                .recordStats()
                .build();
    }
}
