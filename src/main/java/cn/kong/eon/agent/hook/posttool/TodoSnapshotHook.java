package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.SessionSnapshotStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 会话快照（PostTool, order=100）。todo_write 成功后：
 * ① 激活 Todo 状态；② 无进展检测；③ 保存会话快照。
 */
public class TodoSnapshotHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(TodoSnapshotHook.class);

    private final AgentConfig config;
    private final SessionSnapshotStore snapshotStore;
    private final TodoStore todoStore;
    private final LoopDetector loopDetector;

    public TodoSnapshotHook(AgentConfig config, SessionSnapshotStore snapshotStore,
                            TodoStore todoStore, LoopDetector loopDetector) {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.loopDetector = loopDetector;
    }

    @Override
    public String name() {
        return "TodoSnapshotHook";
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

        // 1. 激活 Todo 状态
        if (!state.hasTodoBeenUsed()) {
            state.setTodoBeenUsed(true);
            log.info("[TodoSnapshotHook] Todo 已激活");
        }

        // 2. 无进展检测
        var snapResult = loopDetector.recordTodoSnapshot(todoStore.getAll().toString());
        if (snapResult.warn()) {
            state.addNudge(snapResult.message());
        }

        // 3. 保存快照
        List<TodoItem> todos = todoStore.getAll();
        snapshotStore.save(todos, state.getUsageAccum(), state.getCompressionState());
        log.info("[TodoSnapshotHook] 已保存: todo={} 条, keepFrom={}", todos.size(), state.getCompressionState().getKeepFromMessage());

        return HookResult.ok();
    }
}
