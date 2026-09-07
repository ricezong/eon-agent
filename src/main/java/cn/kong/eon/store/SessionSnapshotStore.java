package cn.kong.eon.store;

import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.SessionSnapshot;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话快照存储。快照落盘与读回，单个 session.json 覆盖写。
 * 摘要与压缩水位线同取自一份 CompressionState，恢复时拼起来内容完整。
 */
public class SessionSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotStore.class);

    private final Path file;
    private final ObjectMapper mapper;

    public SessionSnapshotStore(Path sessionFile, ObjectMapper objectMapper) {
        this.file = sessionFile;
        this.mapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.findAndRegisterModules();
    }

    /**
     * 保存快照。写临时文件后原子替换，避免崩溃留下半截 JSON。
     */
    public void save(List<TodoItem> todoSnapshot,
                     TokenUsage usageAccum,
                     CompressionState compressionState) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setTodoSnapshot(new ArrayList<>(todoSnapshot));
        snapshot.setUsageAccum(usageAccum);
        snapshot.setCompressionState(compressionState);

        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException nonAtomic) {
                // 文件系统不支持原子移动时退化为普通替换，tmp 已写完整，内容仍是完整的
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("会话快照已保存: todo={} 条, keepFrom={}",
                    todoSnapshot.size(), compressionState != null ? compressionState.getKeepFromMessage() : 0);
        } catch (IOException e) {
            log.error("保存会话快照失败", e);
        }
    }

    /**
     * 读回快照。文件不存在或损坏时返回 null，调用方按无快照处理（全量回放）。
     */
    public SessionSnapshot load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(file.toFile(), SessionSnapshot.class);
        } catch (Exception e) {
            log.error("读取会话快照失败: {}", e.getMessage());
            return null;
        }
    }
}
