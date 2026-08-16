package cn.kong.eon.agent.capability;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 管理能力模块。
 *
 * 配置启用时激活。在 todo_write 调用后保存快照。
 */
public class CheckpointManager implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private final AgentConfig config;
    private final CheckpointStore checkpointStore;
    private final TodoStoreAccessor todoStoreAccessor;

    public CheckpointManager(AgentConfig config, CheckpointStore checkpointStore, TodoStoreAccessor todoStoreAccessor) {
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.todoStoreAccessor = todoStoreAccessor;
    }

    @Override
    public String name() { return "CheckpointManager"; }

    @Override
    public boolean isActive(SessionState state) {
        return config.getMode().checkpointEnabled;
    }

    @Override
    public int priority() { return Priority.LOW; }

    @Override
    public void afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!isActive(state)) return;
        if (!"todo_write".equals(toolName) || !success) return;

        checkpointStore.save(
                state.getSessionId(),
                state.getTurnCount(),
                todoStoreAccessor.getAll(),
                state.getUsageAccum(),
                state.getCompressionState(),
                state.getInsights()
        );
        log.info("Checkpoint saved: turn={}", state.getTurnCount());
    }

    /**
     * TodoStore 访问器（避免直接依赖 TodoStore）。
     */
    public interface TodoStoreAccessor {
        java.util.List<cn.kong.eon.model.TodoItem> getAll();
    }
}
