package cn.kong.eon.engine.hook.posttool;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.SessionState;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoItem;
import cn.kong.eon.store.todo.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 会话快照（PostTool, order=200）。
 * todo_write 成功后，将 todo 列表 + token 累计 + 压缩状态写入 state.json。
 * 仅在 {@code snapshot_enabled=true} 时激活。
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
        return "SessionSnapshotHook";
    }

    @Override
    public boolean active(SessionState state) {
        return config.isSnapshotEnabled();
    }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        List<TodoItem> todos = todoStore.getAll();
        snapshotStore.save(todos, state.getUsageAccum(), state.getCompressionState());
        log.info("[SessionSnapshot] 已保存: todo={} 条, replayFrom={}", todos.size(), state.getCompressionState().getReplayFromSeq());

        return HookResult.ok();
    }
}
