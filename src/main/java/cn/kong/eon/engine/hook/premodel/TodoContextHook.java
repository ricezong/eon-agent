package cn.kong.eon.engine.hook.premodel;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.SessionState;
import cn.kong.eon.store.todo.TodoItem;
import cn.kong.eon.store.todo.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 渲染（PreModel, order=20）。
 * TodoStore 有数据时渲染 Todo 列表到上下文；全完成/取消后自动清空。
 */
public class TodoContextHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(TodoContextHook.class);

    private final TodoStore todoStore;

    public TodoContextHook(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "Todo";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        // TodoStore 有数据时渲染 Todo 列表到上下文
        List<TodoItem> todos = todoStore.getAll();
        if (!todos.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
            ctx.setTodo(sb.toString());
            log.debug("[Todo] 已渲染: {} 条", todos.size());
        }
        return HookResult.ok();
    }
}
