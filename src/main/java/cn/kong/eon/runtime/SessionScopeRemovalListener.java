package cn.kong.eon.runtime;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话上下文缓存的淘汰监听器：条目离开缓存时释放 SessionScope 资源。
 * 主动失效不落快照，过期/超容淘汰则落终态快照。
 */
@Component
public class SessionScopeRemovalListener implements RemovalListener<String, SessionScope> {

    private static final Logger log = LoggerFactory.getLogger(SessionScopeRemovalListener.class);

    @Override
    public void onRemoval(String key, SessionScope value, RemovalCause cause) {
        if (value == null) return;
        boolean explicit = cause == RemovalCause.EXPLICIT;
        log.info("会话 {} 移出缓存: cause={}, 落快照={}", key, cause, !explicit);
        value.close(!explicit);
    }
}
