package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.store.InsightsStore;
import cn.kong.eon.store.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Todo 导航能力模块。
 *
 * 按需激活：LLM 调用 todo_write 后激活。
 * 在 beforeModelCall 中渲染 Navigator（Todo 列表 + Insights）到上下文。
 */
public class TodoNavigator implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(TodoNavigator.class);

    private final TodoStore todoStore;
    private final InsightsStore insightsStore;

    public TodoNavigator(TodoStore todoStore, InsightsStore insightsStore) {
        this.todoStore = todoStore;
        this.insightsStore = insightsStore;
    }

    @Override
    public String name() { return "TodoNavigator"; }

    @Override
    public boolean isActive(SessionState state) {
        // LLM 调用过 todo_write 后激活
        return state.hasTodoBeenUsed();
    }

    @Override
    public void beforeModelCall(SessionState state, ContextBuilder ctx) {
        if (!isActive(state)) return;

        StringBuilder sb = new StringBuilder();

        // Pinned: 用户原始请求
        sb.append("## [Pinned] 用户原始请求\n");
        sb.append(state.getUserOriginalInput()).append("\n\n");

        // Pinned: 当前任务清单
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

        // Insights 滚动区
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
    }

    @Override
    public void afterToolExecution(SessionState state, String toolName, boolean success) {
        // 检测到 todo_write 调用，标记激活
        if ("todo_write".equals(toolName) && success) {
            state.setTodoBeenUsed(true);
            log.info("TodoNavigator activated: todo_write called");
        }
    }
}
