package cn.kong.eon.runtime;

import cn.kong.eon.context.CompressionState;
import cn.kong.eon.context.policy.CompressionPolicy;
import cn.kong.eon.engine.guard.ToolCircuitBreaker;
import cn.kong.eon.llm.TokenUsage;
import cn.kong.eon.store.artifact.ArtifactStore;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoStore;
import cn.kong.eon.tool.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级作用域：缓存条目，跨多次 run 存活。
 * 装会话级不变依赖（各 store、策略、熔断器）与跨任务累计量（token、压缩水位）。
 * 同会话并发互斥：tryAcquire() 用 CAS 保证同一时刻只有一个 run。
 */
public final class SessionScope {

    private static final Logger log = LoggerFactory.getLogger(SessionScope.class);

    // ── 不变部分（装配一次）
    private final String sessionId;
    /** 会话账本路径。压缩摘要需要它做兜底文案，故作为会话级不变量存下来。 */
    private final String transcriptPath;
    private final boolean snapshotEnabled;
    private final TranscriptLedger ledger;
    private final TodoStore todoStore;
    private final ArtifactStore artifactStore;
    private final SessionSnapshotStore snapshotStore;
    private final PathResolver pathResolver;
    private final MemoryStore memoryStore;
    private final ToolCircuitBreaker circuitBreaker;
    private final CompressionPolicy compressionPolicy;

    // ── 跨任务累计（落快照）
    private TokenUsage usageAccum;
    private CompressionState compressionState;

    // ── 状态与并发控制
    private final AtomicReference<SessionLifecycle> status = new AtomicReference<>(SessionLifecycle.IDLE);
    private final AtomicReference<RunContext> currentRun = new AtomicReference<>();
    private final ReentrantLock runLock = new ReentrantLock();

    public SessionScope(String sessionId,
                        String transcriptPath,
                        boolean snapshotEnabled,
                        TranscriptLedger ledger,
                        TodoStore todoStore,
                        ArtifactStore artifactStore,
                        SessionSnapshotStore snapshotStore,
                        PathResolver pathResolver,
                        MemoryStore memoryStore,
                        ToolCircuitBreaker circuitBreaker,
                        CompressionPolicy compressionPolicy,
                        TokenUsage usageAccum,
                        CompressionState compressionState) {
        this.sessionId = sessionId;
        this.transcriptPath = transcriptPath;
        this.snapshotEnabled = snapshotEnabled;
        this.ledger = ledger;
        this.todoStore = todoStore;
        this.artifactStore = artifactStore;
        this.snapshotStore = snapshotStore;
        this.pathResolver = pathResolver;
        this.memoryStore = memoryStore;
        this.circuitBreaker = circuitBreaker;
        this.compressionPolicy = compressionPolicy;
        this.usageAccum = usageAccum != null ? usageAccum : TokenUsage.zero();
        this.compressionState = compressionState != null ? compressionState : new CompressionState();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  状态机
    // ═══════════════════════════════════════════════════════════════════

    /** IDLE → RUNNING；已 RUNNING 或 CLOSED 返回 false。 */
    public boolean tryAcquire() {
        return status.compareAndSet(SessionLifecycle.IDLE, SessionLifecycle.RUNNING);
    }

    /** RUNNING → IDLE，清空 currentRun 引用。 */
    public void release() {
        currentRun.set(null);
        status.compareAndSet(SessionLifecycle.RUNNING, SessionLifecycle.IDLE);
    }

    /** 当前状态。 */
    public SessionLifecycle status() {
        return status.get();
    }

    /** 当前 run（供 interrupt 使用）；无任务时为 null。 */
    public RunContext currentRun() {
        return currentRun.get();
    }

    /** 绑定当前 run。由 RunContext 构造时调用。 */
    public void bindRun(RunContext run) {
        currentRun.set(run);
    }

    /** 同会话排队模式（busyPolicy=QUEUE）使用的锁。 */
    public ReentrantLock runLock() {
        return runLock;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  快照与关闭
    // ═══════════════════════════════════════════════════════════════════

    /** 保存终态快照（todo + 累计 token + 压缩水位）。快照开关关闭时为空操作。 */
    public void saveSnapshot() {
        if (!snapshotEnabled) return;
        snapshotStore.save(todoStore.getAll(), usageAccum, compressionState);
    }

    /**
     * 释放会话资源：落终态快照 → 清空窗口块引用 → 标记 CLOSED。
     * 由缓存淘汰监听器调用。
     */
    public void close() {
        close(true);
    }

    /**
     * @param saveSnapshot 是否落终态快照。主动删除会话（目录即将被删）时传 false，
     *                     避免把快照写进一个正在删除的目录。
     */
    public void close(boolean saveSnapshot) {
        if (status.get() == SessionLifecycle.RUNNING) {
            log.error("会话 {} 在 RUNNING 状态被关闭：TTL 配置过短或任务卡死", sessionId);
        }
        if (saveSnapshot) {
            try {
                this.saveSnapshot();
            } catch (Exception e) {
                log.warn("会话 {} 关闭时保存快照失败", sessionId, e);
            }
        }
        try {
            ledger.window().removeBefore(ledger.window().size());
        } catch (Exception e) {
            log.warn("会话 {} 关闭时清空窗口失败", sessionId, e);
        }
        status.set(SessionLifecycle.CLOSED);
        log.info("会话 {} 已关闭并释放资源", sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════

    public String sessionId() { return sessionId; }
    public String transcriptPath() { return transcriptPath; }
    public TranscriptLedger ledger() { return ledger; }
    public TodoStore todoStore() { return todoStore; }
    public ArtifactStore artifactStore() { return artifactStore; }
    public SessionSnapshotStore snapshotStore() { return snapshotStore; }
    public PathResolver pathResolver() { return pathResolver; }
    public MemoryStore memoryStore() { return memoryStore; }
    public ToolCircuitBreaker circuitBreaker() { return circuitBreaker; }
    public CompressionPolicy compressionPolicy() { return compressionPolicy; }

    public TokenUsage usageAccum() { return usageAccum; }

    public void setUsageAccum(TokenUsage usageAccum) {
        this.usageAccum = usageAccum;
    }

    public CompressionState compressionState() {
        return compressionState;
    }

    public void setCompressionState(CompressionState compressionState) {
        this.compressionState = compressionState;
    }
}
