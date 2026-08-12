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
 * Checkpoint 存储。
 * 对应技术方案第 2.4 节。
 * 每次 todo_write 或每 5 轮落盘一次，崩溃后从最新 checkpoint 恢复。
 */
public class CheckpointStore {
    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final Path checkpointDir;
    private final ObjectMapper mapper;
    private final AtomicInteger counter = new AtomicInteger(0);

    public CheckpointStore(Path checkpointDir) {
        this.checkpointDir = checkpointDir;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.findAndRegisterModules();
        try {
            Files.createDirectories(checkpointDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create checkpoint dir", e);
        }
    }

    /**
     * 保存 checkpoint。
     */
    public Checkpoint save(String sessionId, int turnCount,
                           java.util.List<TodoItem> todoSnapshot,
                           TokenUsage usageAccum,
                           CompressionState compressionState,
                           java.util.List<String> insightsSnapshot) {
        int seq = counter.incrementAndGet();
        Checkpoint cp = new Checkpoint();
        cp.setCheckpointId("cp_" + String.format("%03d", seq));
        cp.setSessionId(sessionId);
        cp.setTurnCount(turnCount);
        cp.setTodoSnapshot(new ArrayList<>(todoSnapshot));
        cp.setUsageAccum(usageAccum);
        cp.setCompressionState(compressionState);
        cp.setInsightsSnapshot(new ArrayList<>(insightsSnapshot));
        cp.setCreatedAt(Instant.now());

        Path file = checkpointDir.resolve(cp.getCheckpointId() + ".json");
        try {
            mapper.writeValue(file.toFile(), cp);
            log.info("Checkpoint saved: {} (turn={}, todos={})",
                    cp.getCheckpointId(), turnCount, todoSnapshot.size());
        } catch (IOException e) {
            log.error("Failed to save checkpoint", e);
        }
        return cp;
    }

    /**
     * 加载最新 checkpoint。
     */
    public Checkpoint loadLatest(String sessionId) {
        try (var stream = Files.list(checkpointDir)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().startsWith("cp_"))
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
            if (files.isEmpty()) return null;
            Path latest = files.get(files.size() - 1);
            Checkpoint cp = mapper.readValue(latest.toFile(), Checkpoint.class);
            log.info("Checkpoint loaded: {} (turn={})", cp.getCheckpointId(), cp.getTurnCount());
            return cp;
        } catch (IOException e) {
            log.error("Failed to load checkpoint", e);
            return null;
        }
    }

    public void clearAll() {
        try (var stream = Files.list(checkpointDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("cp_"))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException e) {
            log.warn("Failed to clear checkpoints: {}", e.getMessage());
        }
    }
}
