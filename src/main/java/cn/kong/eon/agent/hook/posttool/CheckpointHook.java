package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checkpoint 管理（PostTool, order=100）。todo_write 成功后保存快照。
 */
public class CheckpointHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(CheckpointHook.class);

    private final AgentConfig config;
    private final CheckpointStore checkpointStore;
    private final TodoStore todoStore;

    public CheckpointHook(AgentConfig config, CheckpointStore checkpointStore, TodoStore todoStore) {
        this.config = config;
        this.checkpointStore = checkpointStore;
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "Checkpoint";
    }

    @Override
    public boolean isActive(SessionState state) {
        return config.isCheckpointEnabled();
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!isActive(state)) return HookResult.ok();
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        checkpointStore.save(
                state.getSessionId(),
                state.getTurnCount(),
                todoStore.getAll(),
                state.getUsageAccum(),
                state.getCompressionState()
        );
        log.info("Checkpoint 已保存: turn={}", state.getTurnCount());

        return HookResult.ok();
    }
}
