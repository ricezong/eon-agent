package cn.kong.eon.runtime;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.context.ContentCompressor;
import cn.kong.eon.context.pipeline.IngestPipeline;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.engine.guard.ToolCircuitBreaker;
import cn.kong.eon.store.artifact.ArtifactStore;
import cn.kong.eon.store.index.SessionIndexStore.SessionSummary;
import cn.kong.eon.store.ledger.TranscriptLedger;
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
 * 内容由原 {@code AgentSession} 构造器的会话装配部分拆出——只负责
 * "建目录 → 读快照 → 回放账本 → 建工作区 → 装配 store 与策略"，
 * <b>不含</b> Hook / dispatcher / listeners 等任务级、请求级对象（那些已迁到 RunContext 与 Spring 单例）。
 */
@Component
public class SessionScopeLoader {

    private static final Logger log = LoggerFactory.getLogger(SessionScopeLoader.class);

    private final AgentConfig config;
    private final ContentCompressor compressor;
    private final MemoryStore memoryStore;
    private final ObjectMapper objectMapper;
    /** 应用级单例：压缩策略与会话无关（水位/档位来自配置，摘要器也是单例）。 */
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
     * @param resumed   恢复已有会话时的索引摘要；新建会话传 null
     */
    public SessionScope load(String sessionId, SessionSummary resumed) {
        // ── 1. 会话目录
        Path sessionBaseDir = Path.of(config.getStorage().getBaseDir()).toAbsolutePath().normalize();
        Path sessionDir = sessionBaseDir.resolve(sessionId);
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            throw new RuntimeException("创建会话目录失败: " + sessionDir, e);
        }

        // ── 2. 存储
        TodoStore todoStore = new TodoStore();
        ArtifactStore artifactStore = new ArtifactStore(sessionDir.resolve("tool-results"));
        SessionSnapshotStore snapshotStore = new SessionSnapshotStore(
                sessionDir.resolve("state.json"), objectMapper);
        IngestPipeline ingestPipeline = createContextPipeline(artifactStore);

        // ── 3. 快照 → 回放起点
        SessionSnapshot snapshot = resumed != null ? snapshotStore.load() : null;
        RestoreMode mode = RestoreMode.of(snapshot, resumed != null ? resumed.messageCount() : 0);
        int replayFrom = mode == RestoreMode.RESUME
                ? snapshot.getCompressionState().getReplayFromSeq() : 0;
        if (mode == RestoreMode.LOAD && snapshot != null) {
            log.warn("会话 {} 快照不自洽（水位 {} / 账本 {} 行 / 摘要 {}），改为全量回放",
                    sessionId, snapshot.getCompressionState().getReplayFromSeq(),
                    resumed.messageCount(),
                    snapshot.getCompressionState().getLastSummary() != null ? "有" : "无");
        }

        // ── 4. 账本回放
        Path jsonlPath = sessionDir.resolve("transcript.jsonl");
        String transcriptPath = jsonlPath.toAbsolutePath().toString();
        TranscriptLedger ledger = new TranscriptLedger(jsonlPath, ingestPipeline, replayFrom, objectMapper);

        if (snapshot != null) {
            log.info("会话 {} 已恢复: 模式 {}, 回放 #{}~{} 共 {} 条, 摘要 {} 字符, 累计 {} tokens",
                    sessionId, mode, replayFrom, resumed.messageCount(),
                    resumed.messageCount() - replayFrom,
                    snapshot.getCompressionState().getLastSummary() != null
                            ? snapshot.getCompressionState().getLastSummary().length() : 0,
                    snapshot.getUsageAccum() != null ? snapshot.getUsageAccum().getTotalTokens() : 0);
        } else if (resumed != null) {
            log.info("会话 {} 无快照（未调用过 todo_write），按全量历史启动", sessionId);
        } else {
            log.info("会话 {} 已初始化, transcript: {}", sessionId, transcriptPath);
        }

        // ── 5. 工作区
        PathResolver pathResolver = createWorkspace(sessionDir);

        // ── 6. 运行时组件（压缩策略为应用级单例，直接注入；熔断器是会话级状态，每会话新建）
        var ldc = config.getLoopDetect();
        ToolCircuitBreaker circuitBreaker = new ToolCircuitBreaker(ldc);

        SessionScope scope = new SessionScope(
                sessionId,
                transcriptPath,
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

        // 快照恢复：todo 与压缩水位
        if (snapshot != null) {
            if (mode == RestoreMode.RESUME && snapshot.getCompressionState() != null) {
                scope.compressionState().setLastSummary(snapshot.getCompressionState().getLastSummary());
                scope.compressionState().setReplayFromSeq(snapshot.getCompressionState().getReplayFromSeq());
            }
            if (snapshot.getTodoSnapshot() != null && !snapshot.getTodoSnapshot().isEmpty()) {
                todoStore.replaceAll(snapshot.getTodoSnapshot(), 0);
            }
        }

        return scope;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部装配
    // ═══════════════════════════════════════════════════════════════════

    private PathResolver createWorkspace(Path sessionDir) {
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

}
