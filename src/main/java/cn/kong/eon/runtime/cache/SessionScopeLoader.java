package cn.kong.eon.runtime.cache;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.context.pipeline.IngestPipeline;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.engine.guard.ToolCircuitBreaker;
import cn.kong.eon.store.artifact.ArtifactStore;
import cn.kong.eon.store.index.SessionMeta;
import cn.kong.eon.store.ledger.LedgerStore;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.snapshot.RestoreMode;
import cn.kong.eon.store.snapshot.SessionSnapshot;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoStore;
import cn.kong.eon.tool.PathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link SessionScope} 装配器：缓存未命中时调用。
 * <p>
 */
@Component
public class SessionScopeLoader {

    private static final Logger log = LoggerFactory.getLogger(SessionScopeLoader.class);

    private final AgentConfig config;
    private final ContentCompressor compressor;
    private final MemoryStore memoryStore;
    private final ObjectMapper objectMapper;
    private final CompressionPolicy compressionPolicy;

    public SessionScopeLoader(AgentConfig config,
                              ContentCompressor compressor,
                              MemoryStore memoryStore,
                              ObjectMapper objectMapper,
                              CompressionPolicy compressionPolicy) {
        this.config = config;
        this.compressor = compressor;
        this.memoryStore = memoryStore;
        this.objectMapper = objectMapper;
        this.compressionPolicy = compressionPolicy;
    }

    /**
     * 装配一个会话上下文（含账本回放）。
     *
     * @param sessionId 会话 ID
     * @param meta   恢复已有会话时的索引摘要；新建会话传 null
     */
    public SessionScope load(String sessionId, SessionMeta meta) {
        // ── 1. 工作区
        Path sessionBaseDir = Path.of(config.getStorage().getBaseDir()).toAbsolutePath().normalize();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        PathResolver pathResolver = createWorkspace(sessionDir);

        // ── 2. 存储
        TodoStore todoStore = new TodoStore();
        ArtifactStore artifactStore = new ArtifactStore(sessionDir.resolve("tool-results"));
        SessionSnapshotStore snapshotStore = new SessionSnapshotStore(sessionDir.resolve("state.json"), objectMapper);
        IngestPipeline ingestPipeline = createContextPipeline(artifactStore);

        // ── 3. 快照 → 回放起点
        Path jsonlPath = sessionDir.resolve("ledger.jsonl");
        long ledgerSize = countJsonlLines(jsonlPath);
        SessionSnapshot snapshot = meta != null ? snapshotStore.load() : null;
        RestoreMode mode = RestoreMode.of(snapshot, ledgerSize);
        int replayFrom = mode == RestoreMode.RESUME ? snapshot.getCompressionState().getReplayFromSeq() : 0;
        if (mode == RestoreMode.LOAD && snapshot != null) {
            log.warn("会话 {} 快照不自洽（水位 {} / 账本 {} 行 / 摘要 {}），改为全量回放",
                    sessionId, snapshot.getCompressionState().getReplayFromSeq(),
                    ledgerSize,
                    snapshot.getCompressionState().getLastSummary() != null ? "有" : "无");
        }

        // ── 4. 账本回放
        String ledgerPath = jsonlPath.toAbsolutePath().toString();
        LedgerStore ledger = new LedgerStore(jsonlPath, ingestPipeline, replayFrom, objectMapper);

        if (snapshot != null) {
            log.info("会话 {} 已恢复: 模式 {}, 回放 #{}~{} 共 {} 条, 摘要 {} 字符, 累计 {} tokens",
                    sessionId, mode, replayFrom, ledgerSize,
                    ledgerSize - replayFrom,
                    snapshot.getCompressionState().getLastSummary() != null
                            ? snapshot.getCompressionState().getLastSummary().length() : 0,
                    snapshot.getUsageAccum() != null ? snapshot.getUsageAccum().getTotalTokens() : 0);
        } else if (meta != null) {
            log.info("会话 {} 无快照（未调用过 todo_write），按全量历史启动", sessionId);
        } else {
            log.info("会话 {} 已初始化, ledger: {}", sessionId, ledgerPath);
        }

        // ── 5. 运行时组件
        var ldc = config.getLoopDetect();
        ToolCircuitBreaker circuitBreaker = new ToolCircuitBreaker(ldc);

        SessionScope scope = new SessionScope(
                sessionId,
                ledgerPath,
                config.isSnapshotEnabled(),
                ledger,
                todoStore,
                artifactStore,
                snapshotStore,
                pathResolver,
                memoryStore,
                circuitBreaker,
                compressionPolicy,
                snapshot != null ? snapshot.getUsageAccum() : null,
                snapshot != null ? snapshot.getCompressionState() : null);

        // ── 6. 快照恢复
        if (snapshot != null) {
            if (mode == RestoreMode.RESUME && snapshot.getCompressionState() != null) {
                scope.compressionState().setLastSummary(snapshot.getCompressionState().getLastSummary());
                scope.compressionState().setReplayFromSeq(snapshot.getCompressionState().getReplayFromSeq());
            }
            if (snapshot.getTodoSnapshot() != null && !snapshot.getTodoSnapshot().isEmpty()) {
                todoStore.replaceAll(snapshot.getTodoSnapshot());
            }
        }

        return scope;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    /** 建会话根目录 + 全部子目录，返回 PathResolver。 */
    private PathResolver createWorkspace(Path sessionDir) {
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }
        for (String sub : List.of("scripts", "download", "upload", "skills", "tool-results")) {
            try {
                Files.createDirectories(sessionDir.resolve(sub));
            } catch (IOException e) {
                throw new RuntimeException("创建工作区子目录失败: " + sub, e);
            }
        }
        return new PathResolver(sessionDir.toAbsolutePath().toString(), config.getTools().isSandboxEnabled());
    }

    private IngestPipeline createContextPipeline(ArtifactStore artifactStore) {
        var ctx = config.getContext();
        log.info("入站管线已装配 (落盘阈值 {} 字符, 保留 {} 字符)",
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
        return new IngestPipeline(compressor, artifactStore,
                ctx.getSpillThresholdChars(), ctx.getSpillKeepChars());
    }

    /** 数 JSONL 非空行数，文件不存在返回 0。 */
    private static long countJsonlLines(Path jsonlPath) {
        if (!Files.exists(jsonlPath)) return 0;
        try (var lines = Files.lines(jsonlPath)) {
            return lines.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            log.warn("读取账本行数失败: {}，按 0 行处理", jsonlPath, e);
            return 0;
        }
    }

}
