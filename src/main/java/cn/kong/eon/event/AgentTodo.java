package cn.kong.eon.event;

import cn.kong.eon.store.todo.TodoItem;

import java.time.Instant;
import java.util.List;

/**
 * 待办清单变更事件。todo_write 执行后发出，内容恒为 TodoStore 的当前全量状态——
 * 全部完成/取消时 store 已被清空，事件带空数组，前端据此撤下卡片。
 */
public record AgentTodo(
        String turnId,
        List<TodoItem> todos,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.todo";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitTodo(this);
    }

    public static AgentTodo now(String turnId, List<TodoItem> todos) {
        return new AgentTodo(turnId, todos, Instant.now());
    }
}
