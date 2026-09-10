package cn.kong.eon.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.session.SessionState;
import cn.kong.eon.store.todo.TodoItem;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话快照（PostTool, order=100）。todo_write 成功后：
 * ① 无进展检测；② 保存会话快照。
 */
public class TodoSnapshotHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(TodoSnapshotHook.class);

    private static final String WARN_MSG = "连续 %s 步 Todo 无变化，请检查是否陷入循环";

    private final AgentConfig config;
    private final SessionSnapshotStore snapshotStore;
    private final TodoStore todoStore;

    // ── 无进展检测状态
    private final int windowSize;
    private final Deque<String> snapshots = new ArrayDeque<>();
    private int stepsWithoutProgress = 0;

    public TodoSnapshotHook(AgentConfig config, SessionSnapshotStore snapshotStore, TodoStore todoStore) {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.windowSize = config.getLoopDetect().getNoProgressSteps();
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
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

        // 无进展检测
        String snapshot = todoStore.getAll().toString();
        snapshots.addLast(snapshot);
        if (snapshots.size() > windowSize) {
            snapshots.removeFirst();
        }

        if (snapshots.size() == windowSize) {
            Set<String> unique = new HashSet<>(snapshots);
            if (unique.size() == 1) {
                stepsWithoutProgress++;
                if (stepsWithoutProgress >= 2) {
                    log.warn("[TodoSnapshotHook] 无进展: 连续 {} 个窗口（{} 步）未变化", stepsWithoutProgress, windowSize);
                    state.addNudge(String.format(WARN_MSG, windowSize * stepsWithoutProgress));
                }
            } else {
                stepsWithoutProgress = 0;
            }
        }

        // 保存快照
        List<TodoItem> todos = todoStore.getAll();
        snapshotStore.save(todos, state.getUsageAccum(), state.getCompressionState());
        log.info("[TodoSnapshotHook] 已保存: todo={} 条, replayFrom={}", todos.size(), state.getCompressionState().getReplayFromSeq());

        return HookResult.ok();
    }

    /** 清空无进展检测状态，在每个任务开始时调用。 */
    public void reset() {
        snapshots.clear();
        stepsWithoutProgress = 0;
    }
}
