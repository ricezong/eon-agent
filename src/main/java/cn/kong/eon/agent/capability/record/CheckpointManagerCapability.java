package cn.kong.eon.agent.capability.record;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 管理能力模块（RECORD 层）。
 * 配置启用时，在 todo_write 调用后保存快照。
 */
public class CheckpointManagerCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManagerCapability.class);

    private final AgentConfig config;
    private final CheckpointStore checkpointStore;
    private final TodoStoreAccessor todoStoreAccessor;

    public CheckpointManagerCapability(AgentConfig config, CheckpointStore checkpointStore, TodoStoreAccessor todoStoreAccessor) {
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.todoStoreAccessor = todoStoreAccessor;
    }

    @Override public String name() { return "CheckpointManager"; }
    @Override public boolean isActive(SessionState state) { return config.getMode().checkpointEnabled; }
    @Override public Layer layer() { return Layer.RECORD; }

    @Override
    public CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!isActive(state)) return CapabilityResult.ok();
        if (!"todo_write".equals(toolName) || !success) return CapabilityResult.ok();

        checkpointStore.save(
                state.getSessionId(),
                state.getTurnCount(),
                todoStoreAccessor.getAll(),
                state.getUsageAccum(),
                state.getCompressionState(),
                state.getInsights()
        );
        log.info("Checkpoint saved: turn={}", state.getTurnCount());

        return CapabilityResult.ok();
    }

    /** TodoStore 访问器接口。 */
    public interface TodoStoreAccessor {
        java.util.List<cn.kong.eon.model.TodoItem> getAll();
    }
}
