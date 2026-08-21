package cn.kong.eon.agent.hook.posttool;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Todo 激活追踪（工具执行后阶段，order=10）。
 * 检测 todo_write 调用成功后标记 state.todoBeenUsed=true。
 * 同时记录 Todo 快照，用于 LoopDetector 的无进展检测。
 */
public class TodoActivationHook implements Hook.PostToolHook {
    private static final Logger log = LoggerFactory.getLogger(TodoActivationHook.class);

    private static final String TODO_WRITE_TOOL = "todo_write";

    private final LoopDetector loopDetector;
    private final TodoStore todoStore;

    public TodoActivationHook(LoopDetector loopDetector, TodoStore todoStore) {
        this.loopDetector = loopDetector;
        this.todoStore = todoStore;
    }

    @Override public String name() { return "TodoActivation"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 10; }

    @Override
    public HookResult afterToolExecution(SessionState state, String toolName, boolean success) {
        if (TODO_WRITE_TOOL.equals(toolName) && success) {
            if (!state.hasTodoBeenUsed()) {
                state.setTodoBeenUsed(true);
                log.info("TodoNavigator activated: todo_write called");
            }
            // 记录 Todo 快照，用于无进展检测
            String snapshot = todoStore.getAll().toString();
            var result = loopDetector.recordTodoSnapshot(snapshot);
            if (result.shouldWarn()) {
                state.addNudge(result.message());
            }
        }
        return HookResult.ok();
    }
}
