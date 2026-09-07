package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.SessionSnapshotStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 会话快照（PostTool, order=100）。todo_write 成功后保存快照。
 */
public class SessionSnapshotHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotHook.class);

    private final AgentConfig config;
    private final SessionSnapshotStore snapshotStore;
    private final TodoStore todoStore;

    public SessionSnapshotHook(AgentConfig config, SessionSnapshotStore snapshotStore, TodoStore todoStore) {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "SessionSnapshot";
    }

    @Override
    public boolean active(SessionState state) {
        return config.isSnapshotEnabled();
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        List<TodoItem> todos = todoStore.getAll();
        snapshotStore.save(todos, state.getUsageAccum(), state.getCompressionState());
        log.info("[SessionSnapshot] 已保存: todo={} 条, keepFrom={}",
                todos.size(), state.getCompressionState().getKeepFromMessage());

        return HookResult.ok();
    }
}
