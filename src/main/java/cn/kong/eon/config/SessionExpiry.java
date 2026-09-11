package cn.kong.eon.config;

import cn.kong.eon.runtime.SessionScope;
import cn.kong.eon.runtime.SessionLifecycle;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;

/**
 * 会话缓存的变长过期策略。
 * <p>
 * 为什么不用 {@code expireAfterAccess}：它无法区分会话状态，
 * 长任务执行期间若长时间无人"访问"缓存也可能被淘汰，导致运行中的上下文被销毁。
 * 这里让 RUNNING 条目自动获得长 TTL，是唯一能真正保证"执行中不被淘汰"的方式。
 */
public final class SessionExpiry implements Expiry<String, SessionScope> {

    private final long idleNanos;
    private final long runningNanos;

    public SessionExpiry(Duration idleTtl, Duration runningTtl) {
        this.idleNanos = idleTtl.toNanos();
        this.runningNanos = runningTtl.toNanos();
    }

    @Override
    public long expireAfterCreate(String key, SessionScope value, long currentTime) {
        return ttl(value);
    }

    @Override
    public long expireAfterUpdate(String key, SessionScope value, long currentTime, long currentDuration) {
        return ttl(value);
    }

    @Override
    public long expireAfterRead(String key, SessionScope value, long currentTime, long currentDuration) {
        return ttl(value);
    }

    private long ttl(SessionScope value) {
        return value.status() == SessionLifecycle.RUNNING ? runningNanos : idleNanos;
    }
}
