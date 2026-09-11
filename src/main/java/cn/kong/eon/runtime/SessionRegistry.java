package cn.kong.eon.runtime;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.store.index.SessionIndexStore.SessionSummary;
import cn.kong.eon.web.exception.SessionBusyException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话上下文注册表：缓存 + 状态机的对外门面。
 * <p>
 * 取代原 {@code AgentRuntime.activeSessions}（那个 Map 用 {@code put} 覆盖，
 * 同 session 并发时后到的请求会把先到的会话实例顶掉，是既有缺陷）。
 * <p>
 * 关键保证：
 * <ul>
 *   <li>同 key 只装配一次——{@code cache.get(key, loader)} 由 Caffeine 保证原子加载；</li>
 *   <li>同 session 互斥执行——{@link SessionScope#tryAcquire()} CAS，第二个请求按 busyPolicy 处理；</li>
 *   <li>淘汰即释放——{@code removalListener} 调 {@code scope.close()}。</li>
 * </ul>
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final Cache<String, SessionScope> cache;
    private final SessionScopeLoader loader;
    private final AgentConfig config;

    public SessionRegistry(Cache<String, SessionScope> sessionCache,
                           SessionScopeLoader loader,
                           AgentConfig config) {
        this.cache = sessionCache;
        this.loader = loader;
        this.config = config;
    }

    /**
     * 获取会话上下文并占用它（IDLE → RUNNING）。
     *
     * @throws SessionBusyException 已有任务在执行且 busyPolicy=REJECT
     */
    public SessionScope acquire(String sessionId, SessionSummary resumed) {
        SessionScope scope = cache.get(sessionId, k -> loader.load(k, resumed));

        // 防御：条目在极窄的时序窗口内被关闭，重建后再占用
        if (scope.status() == SessionStatus.CLOSED) {
            log.warn("会话 {} 缓存条目已关闭，重建", sessionId);
            cache.invalidate(sessionId);
            scope = cache.get(sessionId, k -> loader.load(k, resumed));
        }

        if (scope.tryAcquire()) {
            return scope;
        }

        if (isQueuePolicy()) {
            // 排队模式：阻塞等待前一个任务结束。锁由当前线程持有，release 时按线程归属解锁
            scope.runLock().lock();
            if (!scope.tryAcquire()) {
                scope.runLock().unlock();
                throw new SessionBusyException(sessionId);
            }
            return scope;
        }

        throw new SessionBusyException(sessionId);
    }

    /** 归还会话上下文（RUNNING → IDLE）并刷新 TTL。 */
    public void release(String sessionId) {
        SessionScope scope = cache.getIfPresent(sessionId);
        if (scope == null) {
            return;
        }
        // ReentrantLock 记录持有线程，只有排队模式下抢到锁的当前线程会走到这里
        if (scope.runLock().isHeldByCurrentThread()) {
            scope.runLock().unlock();
        }
        scope.release();
        touch(sessionId);
    }

    /** 显式刷新 TTL：任务结束后重算为空闲 TTL，避免沿用 RUNNING 的长 TTL。 */
    public void touch(String sessionId) {
        Duration idle = Duration.ofMinutes(config.getSession().getCache().getIdleTtlMinutes());
        cache.policy().expireVariably().ifPresent(p -> p.setExpiresAfter(sessionId, idle));
    }

    /** 只查缓存、不触发加载。供 interrupt 等场景使用。 */
    public Optional<SessionScope> find(String sessionId) {
        return Optional.ofNullable(cache.getIfPresent(sessionId));
    }

    /**
     * 使会话上下文失效。删除会话时<b>必须</b>先调用，
     * 否则缓存中的上下文会继续往已删除的目录里写数据。
     */
    public void invalidate(String sessionId) {
        cache.invalidate(sessionId);
    }

    /** 清空缓存，所有条目触发 close()。 */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 缓存统计：命中率、淘汰数、加载耗时。 */
    public CacheStats stats() {
        return cache.stats();
    }

    @PreDestroy
    public void shutdown() {
        log.info("正在关闭 SessionRegistry，缓存统计: {}", cache.stats());
        invalidateAll();
    }

    private boolean isQueuePolicy() {
        return "QUEUE".equalsIgnoreCase(config.getSession().getCache().getBusyPolicy());
    }
}
