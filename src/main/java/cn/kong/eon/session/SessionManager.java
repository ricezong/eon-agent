package cn.kong.eon.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话生命周期管理器。
 * <p>
 * 维护活跃会话的 {@link AgentSession} 实例，提供创建、获取、销毁操作。
 * 使用 {@link ConcurrentHashMap} 保证线程安全。
 */
@Component
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    /** 注册会话。 */
    public void put(AgentSession session) {
        sessions.put(session.getSessionId(), session);
        log.info("Session registered: {}", session.getSessionId());
    }

    /** 获取会话，不存在返回 null。 */
    public AgentSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /** 是否存在指定会话。 */
    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** 销毁并移除会话，释放资源。 */
    public void remove(String sessionId) {
        AgentSession session = sessions.remove(sessionId);
        if (session != null) {
            session.destroy();
            log.info("Session destroyed: {}", sessionId);
        }
    }

    /** 获取所有活跃会话 ID。 */
    public List<String> listSessionIds() {
        return new ArrayList<>(sessions.keySet());
    }

    /** 获取所有活跃会话。 */
    public List<AgentSession> listSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * 清理超时会话（最后活跃时间早于 threshold 的会话）。
     *
     * @param threshold 超时阈值
     * @return 清理的会话数
     */
    public int cleanupExpired(Instant threshold) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, AgentSession> entry : sessions.entrySet()) {
            if (entry.getValue().getLastActiveAt().isBefore(threshold)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            remove(id);
        }
        return toRemove.size();
    }

    /** 销毁全部会话（应用关闭时调用）。 */
    public void destroyAll() {
        for (String id : List.copyOf(sessions.keySet())) {
            remove(id);
        }
        log.info("All sessions destroyed (count was {})", sessions.size());
    }
}
