package cn.kong.eon.engine.hook.premodel;

import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.store.todo.TodoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Todo 渲染（PreModel, order=20）。TodoStore 有数据时渲染 Todo 列表到上下文。
 */
@Component
public class TodoContextHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(TodoContextHook.class);

    @Override
    public String name() {
        return "Todo";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public HookResult beforeModelCall(RunContext r) {
        List<TodoItem> todos = r.session().todoStore().getAll();
        if (!todos.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
            r.turn().prompt().setTodo(sb.toString());
            log.debug("[Todo] 已渲染: {} 条", todos.size());
        }
        return HookResult.ok();
    }
}
