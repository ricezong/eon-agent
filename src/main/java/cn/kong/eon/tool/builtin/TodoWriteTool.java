package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * todo_write 工具：创建/更新任务清单（全量替换语义）。
 * 校验单一焦点与依赖完整性。参数类型由 ArgumentSanitizer 统一清洗。
 */
public class TodoWriteTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();

        Map<String, Map<String, Object>> itemSchema = new LinkedHashMap<>();
        itemSchema.put("id", Map.of(
                "type", "string",
                "description", "任务 ID，格式如 t1/t2/t3（字符串，不要用纯数字）"
        ));
        itemSchema.put("content", Map.of(
                "type", "string",
                "description", "任务内容描述（一句话，必填）",
                "required", true
        ));
        itemSchema.put("status", Map.of(
                "type", "string",
                "description", "任务状态枚举：pending(待办) | in_progress(进行中，同时最多1个) | completed(已完成) | blocked(阻塞，需填block_reason) | cancelled(已取消)"
        ));
        itemSchema.put("priority", Map.of(
                "type", "string",
                "description", "优先级：high | medium | low（默认 medium）"
        ));

        props.put("todos", Map.of(
                "type", "array",
                "description", "完整 Todo 列表。全量替换语义：传入什么就是什么，未传入的会被清除。每个元素是对象。",
                "required", true,
                "items", itemSchema
        ));

        String desc = "创建或更新任务清单（全量替换语义）。"
                + "约束：同时最多 1 个 in_progress；depends_on 未完成的不得标 in_progress；"
                + "空数组表示清空列表。";

        return new ToolDescriptor(
                "todo_write",
                desc,
                ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("todo_write", desc, props),
                new TodoWriteTool()
        );
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        try {
            Object todosRaw = arguments.get("todos");
            if (todosRaw == null) {
                return ToolOutcome.failure("缺少 'todos' 参数。请传入对象数组，例如：[{\"id\":\"t1\",\"content\":\"搜索\",\"status\":\"pending\",\"priority\":\"high\"}]");
            }

            // ArgumentSanitizer 已保证类型正确
            List<?> list = (List<?>) todosRaw;

            if (list.isEmpty()) {
                context.todoStore().clear();
                return ToolOutcome.success("Todo 列表已清空。");
            }

            List<TodoItem> todoList = new ArrayList<>();
            int autoIdCounter = 1;
            for (Object item : list) {
                TodoItem todo = convertToTodoItem(item, autoIdCounter);
                if (todo.getId() == null || todo.getId().isBlank()) {
                    todo.setId("t" + autoIdCounter);
                }
                autoIdCounter++;
                todoList.add(todo);
            }

            if (!context.todoStore().validateSingleFocus(todoList)) {
                return ToolOutcome.failure("同时最多一个 in_progress 任务。请检查 status 字段，确保只有一个 in_progress。");
            }

            if (!context.todoStore().validateDependencies(todoList)) {
                return ToolOutcome.failure("depends_on 未完成的 todo 不得标 in_progress。");
            }

            List<TodoItem> result = context.todoStore().replaceAll(todoList, state.getTurnCount());

            return ToolOutcome.success(renderTodoList(result));
        } catch (Exception e) {
            log.error("todo_write failed", e);
            return ToolOutcome.failure(e.getMessage());
        }
    }

    private TodoItem convertToTodoItem(Object item, int autoId) {
        // ArgumentSanitizer 已保证数组元素类型为 Map
        if (item instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                map.put(String.valueOf(e.getKey()), e.getValue());
            }

            TodoItem todo = new TodoItem();

            Object idVal = map.get("id");
            if (idVal != null) {
                todo.setId(String.valueOf(idVal));
            }

            // content 兼容 task/text/title/description
            Object contentVal = map.get("content");
            if (contentVal == null) contentVal = map.get("task");
            if (contentVal == null) contentVal = map.get("text");
            if (contentVal == null) contentVal = map.get("title");
            if (contentVal == null) contentVal = map.get("description");
            todo.setContent(contentVal != null ? String.valueOf(contentVal) : "(未命名任务)");

            String statusStr = map.get("status") != null ? String.valueOf(map.get("status")) : "pending";
            todo.setStatus(parseStatus(statusStr));

            Object priorityVal = map.get("priority");
            todo.setPriority(priorityVal != null ? String.valueOf(priorityVal) : "medium");

            Object deps = map.get("depends_on");
            if (deps == null) deps = map.get("dependsOn");
            if (deps instanceof List<?> depList) {
                List<String> depIds = new ArrayList<>();
                for (Object d : depList) depIds.add(String.valueOf(d));
                todo.setDependsOn(depIds);
            }

            todo.setNotes(map.get("notes") != null ? String.valueOf(map.get("notes")) : null);
            todo.setBlockReason(map.get("block_reason") != null ? String.valueOf(map.get("block_reason")) : null);
            return todo;
        }

        throw new IllegalStateException("Unexpected item type: " + item.getClass());
    }

    /** 兼容多种 status 写法。 */
    private TodoStatus parseStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) return TodoStatus.PENDING;
        String normalized = statusStr.trim().toLowerCase().replace("-", "_").replace(" ", "_");
        return switch (normalized) {
            case "pending", "todo", "to_do", "not_started", "waiting" -> TodoStatus.PENDING;
            case "in_progress", "inprogress", "running", "active", "doing" -> TodoStatus.IN_PROGRESS;
            case "completed", "complete", "done", "finished", "success" -> TodoStatus.COMPLETED;
            case "blocked", "block", "stuck", "error", "failed" -> TodoStatus.BLOCKED;
            case "cancelled", "canceled", "cancel", "skipped", "skip" -> TodoStatus.CANCELLED;
            default -> {
                log.warn("Unknown status '{}', defaulting to PENDING", statusStr);
                yield TodoStatus.PENDING;
            }
        };
    }

    private String renderTodoList(List<TodoItem> todos) {
        if (todos.isEmpty()) {
            return "Todo 列表已清空。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务清单（").append(todos.size()).append(" 项）：\n");
        for (TodoItem t : todos) {
            sb.append(t.toString()).append("\n");
        }
        long completed = todos.stream().filter(x -> x.getStatus() == TodoStatus.COMPLETED).count();
        sb.append("进度：").append(completed).append("/").append(todos.size()).append(" 完成");
        return sb.toString();
    }
}
