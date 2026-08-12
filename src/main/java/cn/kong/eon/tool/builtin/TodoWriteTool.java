package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * todo_write 工具：创建/更新任务清单。
 * 对应技术方案第 5.6 节。
 * 全量替换语义，校验单一焦点与依赖完整性。
 *
 * todos 参数格式（对象数组）：
 * [
 *   {"id": "t1", "content": "搜索下载源", "status": "pending", "priority": "high"},
 *   {"id": "t2", "content": "提取链接", "status": "pending", "priority": "high"}
 * ]
 *
 * 容错策略：
 * - id 缺失时自动生成（t1, t2, ...）
 * - id 为整数时转为字符串
 * - status 缺失时默认 pending；兼容 "todo"/"to_do"/"in-progress" 等变体
 * - content 缺失时尝试用 "task" 字段
 * - 字符串元素自动转为 {content: 字符串, status: pending}
 */
public class TodoWriteTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();

        // todos 是对象数组，items 描述每个 todo 的结构
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
                cn.kong.eon.model.ToolPermission.RESTRICTED_WRITE,
                ToolDescriptor.buildSpec("todo_write", desc, props),
                new TodoWriteTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        try {
            Object todosRaw = arguments.get("todos");
            if (todosRaw == null) {
                return "[ERROR] 缺少 'todos' 参数。请传入对象数组，例如：[{\"id\":\"t1\",\"content\":\"搜索\",\"status\":\"pending\",\"priority\":\"high\"}]";
            }

            // 容错：LLM 有时会把数组序列化成字符串嵌入 JSON（如 {"todos": "[{...}]"}）
            // 此时 todosRaw 是 String，尝试再次解析为 JSON 数组
            List<?> list;
            if (todosRaw instanceof List<?> l) {
                list = l;
            } else if (todosRaw instanceof String s) {
                list = parseJsonArray(s);
                if (list == null) {
                    return "[ERROR] 'todos' 必须是数组。当前是 String 且无法解析为 JSON 数组。"
                            + "请传入真正的数组，例如：[{\"id\":\"t1\",\"content\":\"搜索\",\"status\":\"pending\"}]";
                }
                log.info("todo_write: 'todos' 是字符串，已自动解析为数组（{} 项）", list.size());
            } else {
                return "[ERROR] 'todos' 必须是数组。当前类型: " + todosRaw.getClass().getSimpleName();
            }

            if (list.isEmpty()) {
                context.todoStore().clear();
                return "Todo 列表已清空。";
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
                return "[ERROR] 同时最多一个 in_progress 任务。请检查 status 字段，确保只有一个 in_progress。";
            }

            if (!context.todoStore().validateDependencies(todoList)) {
                return "[ERROR] depends_on 未完成的 todo 不得标 in_progress。";
            }

            List<TodoItem> result = context.todoStore().replaceAll(todoList, state.getTurnCount());

            context.checkpointStore().save(
                    state.getSessionId(),
                    state.getTurnCount(),
                    result,
                    state.getUsageAccum(),
                    state.getCompressionState(),
                    state.getInsights()
            );

            return renderTodoList(result);
        } catch (Exception e) {
            log.error("todo_write failed", e);
            return "[ERROR] " + e.getMessage();
        }
    }

    /**
     * 将字符串解析为 JSON 数组。
     * 用于容错 LLM 把数组序列化成字符串的情况（如 {"todos": "[{...}]"}）。
     * 支持数组字符串（"[...]"）和单对象字符串（"{...}"，自动包装为单元素列表）。
     *
     * @param json 字符串
     * @return 解析后的 List，解析失败或非数组/对象时返回 null
     */
    private List<?> parseJsonArray(String json) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Object parsed = mapper.readValue(trimmed, new TypeReference<Object>() {});
            if (parsed instanceof List<?> l) {
                return l;
            }
            if (parsed instanceof Map<?, ?> m) {
                // 单对象自动包装为单元素列表
                return List.of(m);
            }
            return null;
        } catch (Exception e) {
            log.debug("todo_write: 字符串解析为 JSON 数组失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 容错转换：支持 Map（对象）、String（自动转对象）、其他类型转字符串。
     */
    @SuppressWarnings("unchecked")
    private TodoItem convertToTodoItem(Object item, int autoId) {
        // 情况 1：字符串 → 自动转为 {content: 字符串, status: pending}
        if (item instanceof String s) {
            log.debug("todo_write: 字符串元素自动转为对象, content={}", s);
            TodoItem todo = new TodoItem();
            todo.setId("t" + autoId);
            todo.setContent(s);
            todo.setStatus(TodoStatus.PENDING);
            todo.setPriority("medium");
            return todo;
        }

        // 情况 2：Map（对象）
        if (item instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                map.put(String.valueOf(e.getKey()), e.getValue());
            }

            TodoItem todo = new TodoItem();

            // id：支持 String/Integer/Long，统一转 String
            Object idVal = map.get("id");
            if (idVal != null) {
                todo.setId(String.valueOf(idVal));
            }

            // content：优先 "content"，兼容 "task"/"text"/"title"
            Object contentVal = map.get("content");
            if (contentVal == null) contentVal = map.get("task");
            if (contentVal == null) contentVal = map.get("text");
            if (contentVal == null) contentVal = map.get("title");
            if (contentVal == null) contentVal = map.get("description");
            todo.setContent(contentVal != null ? String.valueOf(contentVal) : "(未命名任务)");

            // status：兼容多种写法
            String statusStr = map.get("status") != null ? String.valueOf(map.get("status")) : "pending";
            todo.setStatus(parseStatus(statusStr));

            // priority
            Object priorityVal = map.get("priority");
            todo.setPriority(priorityVal != null ? String.valueOf(priorityVal) : "medium");

            // depends_on
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

        // 情况 3：其他类型 → 转字符串作为 content
        log.warn("todo_write: 未知元素类型 {}，转为字符串", item.getClass().getSimpleName());
        TodoItem todo = new TodoItem();
        todo.setId("t" + autoId);
        todo.setContent(String.valueOf(item));
        todo.setStatus(TodoStatus.PENDING);
        todo.setPriority("medium");
        return todo;
    }

    /**
     * 容错解析 status，兼容多种写法。
     */
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
