package cn.kong.eon.agent.hook.premodel;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.store.InsightsStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 导航渲染（模型调用前阶段，order=20）。
 * todo_write 调用后激活，渲染 Todo 列表 + Insights 到 ContextBuilder。
 * 用户原始请求已在 transcript 第一条 UserMessage 中，不在此重复注入。
 */
public class TodoNavigatorHook implements Hook.PreModelHook {
    private static final Logger log = LoggerFactory.getLogger(TodoNavigatorHook.class);

    private final TodoStore todoStore;
    private final InsightsStore insightsStore;

    public TodoNavigatorHook(TodoStore todoStore, InsightsStore insightsStore) {
        this.todoStore = todoStore;
        this.insightsStore = insightsStore;
    }

    @Override public String name() { return "TodoNavigator"; }
    @Override public boolean isActive(SessionState state) { return state.hasTodoBeenUsed(); }
    @Override public int order() { return 20; }

    @Override
    public HookResult beforeModelCall(SessionState state, ContextBuilder ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("## [Pinned] 当前任务清单\n");
        List<TodoItem> todos = todoStore.getAll();
        if (todos.isEmpty()) {
            sb.append("（暂无任务）\n");
        } else {
            for (TodoItem t : todos) {
                sb.append(t.toString()).append("\n");
            }
        }
        sb.append("\n");

        List<String> insights = insightsStore.getAll();
        if (!insights.isEmpty()) {
            sb.append("## [Insights] 关键发现（最新在前）\n");
            int idx = 1;
            for (String insight : insights) {
                sb.append(idx++).append(". ").append(insight).append("\n");
            }
        }

        ctx.setNavigator(sb.toString());
        log.debug("Navigator rendered: {} chars", sb.length());

        return HookResult.ok();
    }
}
