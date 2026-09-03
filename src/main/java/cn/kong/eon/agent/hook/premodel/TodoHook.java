package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 渲染（PreModel, order=20）。
 * todo_write 调用后激活，渲染 Todo 列表到上下文。
 */
public class TodoHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(TodoHook.class);

    private final TodoStore todoStore;

    public TodoHook(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override
    public String name() {
        return "Todo";
    }

    @Override
    public boolean active(SessionState state) {
        return state.hasTodoBeenUsed();
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        StringBuilder sb = new StringBuilder("<todo>\n");
        List<TodoItem> todos = todoStore.getAll();
        if (todos.isEmpty()) {
            sb.append("（暂无任务）\n");
        } else {
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
        }
        sb.append("</todo>");

        ctx.setTodo(sb.toString());
        log.debug("[Todo] 已渲染: {} 字符", sb.length());

        return HookResult.ok();
    }
}
