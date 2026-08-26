package cn.kong.eon.store;

import cn.kong.eon.model.Checkpoint;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checkpoint 存储。每轮 todo_write 后保存快照。
 */
public class CheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final Path checkpointDir;
    private final ObjectMapper mapper;
    private final AtomicInteger counter = new AtomicInteger(0); // 自增 ID 计数器

    public CheckpointStore(Path checkpointDir, ObjectMapper objectMapper) {
        this.checkpointDir = checkpointDir;
        this.mapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.findAndRegisterModules();
        try {
            Files.createDirectories(checkpointDir);
        } catch (IOException e) {
            throw new RuntimeException("创建 checkpoint 目录失败", e);
        }
    }

    /**
     * 保存 checkpoint。
     */
    public Checkpoint save(String sessionId, int turnCount,
                           java.util.List<TodoItem> todoSnapshot,
                           TokenUsage usageAccum,
                           CompressionState compressionState) {
        int seq = counter.incrementAndGet();
        Checkpoint cp = new Checkpoint();
        cp.setCheckpointId("cp_" + String.format("%03d", seq));
        cp.setSessionId(sessionId);
        cp.setTurnCount(turnCount);
        cp.setTodoSnapshot(new ArrayList<>(todoSnapshot));
        cp.setUsageAccum(usageAccum);
        cp.setCompressionState(compressionState);
        cp.setCreatedAt(Instant.now());

        Path file = checkpointDir.resolve(cp.getCheckpointId() + ".json");
        try {
            mapper.writeValue(file.toFile(), cp);
            log.info("Checkpoint 已保存: {} (turn={}, todos={})",
                    cp.getCheckpointId(), turnCount, todoSnapshot.size());
        } catch (IOException e) {
            log.error("保存 checkpoint 失败", e);
        }
        return cp;
    }
}
