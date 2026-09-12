package cn.kong.eon.store.index;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.store.db.SessionIndexRepository;
import cn.kong.eon.store.db.SessionIndexRepository.SessionIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话索引存储。从 SQLite 查询会话索引，硬删除时同时删除会话目录。
 */
@Component
public class SessionIndexStore {

    private static final Logger log = LoggerFactory.getLogger(SessionIndexStore.class);

    private final Path baseDir;
    private final SessionIndexRepository indexRepo;

    public SessionIndexStore(AgentConfig config, SessionIndexRepository indexRepo) {
        this.baseDir = Path.of(config.getStorage().getBaseDir()).toAbsolutePath().normalize();
        this.indexRepo = indexRepo;
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new RuntimeException("创建存储根目录失败: " + this.baseDir, e);
        }
    }



    public List<SessionMeta> list(String userId) {
        return indexRepo.list(userId).stream()
                .map(this::toMeta)
                .sorted(Comparator.comparing(SessionMeta::lastActivityAt).reversed())
                .toList();
    }

    public Optional<SessionMeta> find(String userId, String idOrPrefix) {
        return indexRepo.find(userId, idOrPrefix).map(this::toMeta);
    }

    /** 硬删除：删除 SQLite 记录 + 会话目录。 */
    public boolean delete(String userId, String sessionId) {
        boolean deleted = indexRepo.delete(userId, sessionId);
        if (deleted) {
            Path sessionDir = baseDir.resolve(sessionId);
            deleteDirectory(sessionDir);
            log.info("会话已删除: {}", sessionId);
        }
        return deleted;
    }

    /** 新建会话时写入索引。 */
    public void insert(String sessionId, String userId, String title) {
        indexRepo.insert(sessionId, userId, title);
    }

    /** 更新活跃时间与账本消息总数。任务结束时调用。 */
    public void touch(String sessionId, int messageCount) {
        indexRepo.touch(sessionId, messageCount);
    }

    /** 用户消息数 +1。已有会话收到新用户消息时调用。 */
    public void incrementUserMessageCount(String sessionId) {
        indexRepo.incrementUserMessageCount(sessionId);
    }

    private SessionMeta toMeta(SessionIndex idx) {
        return new SessionMeta(
                idx.sessionId(),
                idx.title() != null ? idx.title() : idx.sessionId(),
                idx.lastActiveAt(),
                idx.messageCount(),
                idx.userMessageCount()
        );
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.error("删除会话目录失败: {}", dir, e);
        }
    }
}
