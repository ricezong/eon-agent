package cn.kong.eon.api.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.AgentTodo;
import cn.kong.eon.event.SessionStart;
import cn.kong.eon.runtime.cache.SessionRegistry;
import cn.kong.eon.store.index.SessionIndexStore;
import cn.kong.eon.store.ledger.LedgerReplayer;
import cn.kong.eon.store.snapshot.SessionSnapshot;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoItem;
import cn.kong.eon.api.dto.SessionListItem;
import cn.kong.eon.api.sse.EventFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话管理：索引查询/删除（含缓存失效）、账本回放与事件格式化。
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);

    private final AgentConfig config;
    private final SessionIndexStore indexStore;
    private final SessionRegistry registry;
    private final LedgerReplayer replayer;
    private final EventFormatter formatter;
    private final ObjectMapper objectMapper;

    public SessionServiceImpl(AgentConfig config,
                              SessionIndexStore indexStore,
                              SessionRegistry registry,
                              LedgerReplayer replayer,
                              EventFormatter formatter,
                              ObjectMapper objectMapper) {
        this.config = config;
        this.indexStore = indexStore;
        this.registry = registry;
        this.replayer = replayer;
        this.formatter = formatter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SessionListItem> listSessions(String userId) {
        var sessions = indexStore.list(userId);
        List<SessionListItem> result = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            var s = sessions.get(i);
            result.add(new SessionListItem(
                    i + 1,
                    s.sessionId(),
                    s.title(),
                    s.userMessageCount(),
                    s.lastActivityAt().toString()
            ));
        }
        return result;
    }

    @Override
    public boolean deleteSession(String userId, String sessionId) {
        // 必须先失效缓存，否则缓存中的 SessionScope 会往已删除的目录继续写数据
        registry.invalidate(sessionId);
        boolean deleted = indexStore.delete(userId, sessionId);
        log.info("删除会话 {}: {}", sessionId, deleted ? "成功" : "未找到");
        return deleted;
    }

    @Override
    public List<Map<String, Object>> getSessionEvents(String sessionId) {
        List<AgentEvent> events = new ArrayList<>();
        // session.start 不落盘，回放时补发首帧，使两条链路的事件形状一致
        events.add(SessionStart.now(sessionId, null));
        events.addAll(replayer.replay(ledgerPath(sessionId)));
        // 待办是会话级状态、不在账本里，回放末尾补发一次，让遗留的未完成项也能呈现
        List<TodoItem> todos = loadTodos(sessionId);
        if (!todos.isEmpty()) {
            events.add(AgentTodo.now(null, todos));
        }

        List<Map<String, Object>> rendered = new ArrayList<>(events.size());
        for (AgentEvent event : events) {
            rendered.add(formatter.format(event, sessionId));
        }
        return rendered;
    }

    /**
     * 取会话当前的待办列表。优先读已加载的会话上下文，
     * 缓存未命中时（服务重启后打开旧会话）回退读 state.json 快照。
     */
    private List<TodoItem> loadTodos(String sessionId) {
        return registry.find(sessionId)
                .map(scope -> scope.todoStore().getAll())
                .orElseGet(() -> {
                    SessionSnapshot snap = new SessionSnapshotStore(statePath(sessionId), objectMapper).load();
                    return snap != null && snap.getTodoSnapshot() != null ? snap.getTodoSnapshot() : List.of();
                });
    }

    /** 指定会话的账本路径（不需要会话已加载）。 */
    private Path ledgerPath(String sessionId) {
        return sessionFile(sessionId, "ledger.jsonl");
    }

    /** 指定会话的快照路径（不需要会话已加载）。 */
    private Path statePath(String sessionId) {
        return sessionFile(sessionId, "state.json");
    }

    private Path sessionFile(String sessionId, String name) {
        return Path.of(config.getStorage().getBaseDir())
                .toAbsolutePath().normalize()
                .resolve(sessionId)
                .resolve(name);
    }
}
