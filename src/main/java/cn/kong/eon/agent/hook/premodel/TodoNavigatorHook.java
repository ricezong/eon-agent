package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 导航渲染（PreModel, order=20）。
 * todo_write 调用后激活，渲染 Todo 列表到上下文。
 */
public class TodoNavigatorHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(TodoNavigatorHook.class);

    private final TodoStore todoStore;

    public TodoNavigatorHook(TodoStore todoStore) {
        this.todoStore = todoStore;
    }

    @Override public String name() { return "TodoNavigator"; }
    @Override public boolean isActive(SessionState state) { return state.hasTodoBeenUsed(); }
    @Override public int order() { return 20; }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        StringBuilder sb = new StringBuilder("<navigator>\n");
        List<TodoItem> todos = todoStore.getAll();
        if (todos.isEmpty()) {
            sb.append("（暂无任务）\n");
        } else {
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
        }
        sb.append("</navigator>");

        ctx.setNavigator(sb.toString());
        log.debug("Navigator rendered: {} chars", sb.length());

        return HookResult.ok();
    }
}
