package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** todo_read 工具：读取当前任务清单。 */
public class TodoReadTool implements ToolExecutor {

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        return new ToolDescriptor(
                "todo_read",
                "读取当前任务清单，返回完整列表与进度统计。",
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("todo_read",
                        "读取当前任务清单，返回完整列表与进度统计。",
                        props),
                new TodoReadTool()
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        List<TodoItem> todos = context.todoStore().getAll();
        if (todos.isEmpty()) {
            return ToolOutcome.success("当前无任务。请先使用 todo_write 创建任务清单。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前任务清单（").append(todos.size()).append(" 项）：\n");
        for (TodoItem t : todos) {
            sb.append(t.toString()).append("\n");
        }

        long completed = todos.stream().filter(x -> x.getStatus() == TodoStatus.COMPLETED).count();
        long inProgress = todos.stream().filter(x -> x.getStatus() == TodoStatus.IN_PROGRESS).count();
        long pending = todos.stream().filter(x -> x.getStatus() == TodoStatus.PENDING).count();
        long blocked = todos.stream().filter(x -> x.getStatus() == TodoStatus.BLOCKED).count();

        sb.append("\n进度统计：\n");
        sb.append("  完成: ").append(completed).append("\n");
        sb.append("  进行中: ").append(inProgress).append("\n");
        sb.append("  待办: ").append(pending).append("\n");
        sb.append("  阻塞: ").append(blocked).append("\n");
        sb.append("  完成率: ").append(String.format("%.0f%%", 100.0 * completed / todos.size()));

        return ToolOutcome.success(sb.toString());
    }
}
