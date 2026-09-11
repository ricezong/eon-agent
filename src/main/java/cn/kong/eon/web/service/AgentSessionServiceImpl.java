package cn.kong.eon.web.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.runtime.SessionRegistry;
import cn.kong.eon.store.index.SessionIndexStore;
import cn.kong.eon.store.ledger.TranscriptReplayer;
import cn.kong.eon.web.dto.SessionListItem;
import cn.kong.eon.web.sse.AgentEventFormatter;
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
 * <p>
 * 承接原 {@code AgentRuntime.getTranscriptPath()} 与 controller 里的回放/格式化逻辑。
 */
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionServiceImpl.class);

    private final AgentConfig config;
    private final SessionIndexStore indexStore;
    private final SessionRegistry registry;
    private final ObjectMapper objectMapper;

    public AgentSessionServiceImpl(AgentConfig config,
                                   SessionIndexStore indexStore,
                                   SessionRegistry registry,
                                   ObjectMapper objectMapper) {
        this.config = config;
        this.indexStore = indexStore;
        this.registry = registry;
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
                    s.messageCount(),
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
        TranscriptReplayer replayer = new TranscriptReplayer(objectMapper);
        List<AgentEvent> events = replayer.replay(transcriptPath(sessionId));
        // 与实时 SSE 共用同一个格式化器，保证恢复渲染与实时渲染结构一致
        AgentEventFormatter formatter = new AgentEventFormatter();
        List<Map<String, Object>> rendered = new ArrayList<>(events.size());
        for (AgentEvent event : events) {
            rendered.add(event.accept(formatter));
        }
        return rendered;
    }

    /** 指定会话的账本路径（不需要会话已加载）。 */
    private Path transcriptPath(String sessionId) {
        return Path.of(config.getStorage().getBaseDir())
                .toAbsolutePath().normalize()
                .resolve(sessionId)
                .resolve("transcript.jsonl");
    }
}
