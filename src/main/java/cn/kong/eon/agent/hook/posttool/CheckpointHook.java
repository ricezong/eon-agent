package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 管理（工具执行后阶段，order=100）。
 * 配置启用时，在 todo_write 调用后保存快照。
 */
public class CheckpointHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(CheckpointHook.class);

    private final AgentConfig config;
    private final CheckpointStore checkpointStore;
    private final TodoStoreAccessor todoStoreAccessor;

    public CheckpointHook(AgentConfig config, CheckpointStore checkpointStore, TodoStoreAccessor todoStoreAccessor) {
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.todoStoreAccessor = todoStoreAccessor;
    }

    @Override public String name() { return "Checkpoint"; }
    @Override public boolean isActive(SessionState state) { return config.getMode().checkpointEnabled; }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!isActive(state)) return HookResult.ok();
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        checkpointStore.save(
                state.getSessionId(),
                state.getTurnCount(),
                todoStoreAccessor.getAll(),
                state.getUsageAccum(),
                state.getCompressionState(),
                state.getInsights()
        );
        log.info("Checkpoint saved: turn={}", state.getTurnCount());

        return HookResult.ok();
    }

    /** TodoStore 访问器接口。 */
    public interface TodoStoreAccessor {
        java.util.List<cn.kong.eon.model.TodoItem> getAll();
    }
}
