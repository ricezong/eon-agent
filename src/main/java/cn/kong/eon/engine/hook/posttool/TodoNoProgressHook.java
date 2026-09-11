package cn.kong.eon.engine.hook.posttool;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.SessionState;
import cn.kong.eon.store.todo.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Todo 无进展检测（PostTool, order=100）。
 * todo_write 成功后，滑动窗口比对 Todo 快照，连续 N 步无变化则注入 nudge 警告。
 * <p>
 * 独立于快照开关——即使 {@code snapshot_enabled=false}，无进展检测仍然工作。
 */
public class TodoNoProgressHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(TodoNoProgressHook.class);

    private static final String WARN_MSG = "连续 %s 步 Todo 无变化，请检查是否陷入循环";

    private final int windowSize;
    private final TodoStore todoStore;

    private final Deque<String> snapshots = new ArrayDeque<>();
    private int stepsWithoutProgress = 0;

    public TodoNoProgressHook(AgentConfig.LoopDetectConfig loopDetectConfig, TodoStore todoStore) {
        this.windowSize = loopDetectConfig.getNoProgressSteps();
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "TodoNoProgressHook";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (!"todo_write".equals(toolName) || !success) return HookResult.ok();

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
                    log.warn("[TodoNoProgress] 无进展: 连续 {} 个窗口（{} 步）未变化",
                            stepsWithoutProgress, windowSize);
                    state.addNudge(String.format(WARN_MSG, windowSize * stepsWithoutProgress));
                }
            } else {
                stepsWithoutProgress = 0;
            }
        }

        return HookResult.ok();
    }

    /** 清空无进展检测状态，在每个任务开始时调用。 */
    public void reset() {
        snapshots.clear();
        stepsWithoutProgress = 0;
    }
}
