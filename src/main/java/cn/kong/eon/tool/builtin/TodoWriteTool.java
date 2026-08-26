package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * todo_write 工具：创建和管理结构化任务列表。
 * Schema 对齐方案：content/id/status + merge。
 * merge=true 按 id 合并，merge=false 全量替换。
 */
public class TodoWriteTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    private final ObjectMapper objectMapper;

    public TodoWriteTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        Object todosObj = arguments.get("todos");
        if (!(todosObj instanceof List<?> todosList) || todosList.isEmpty()) {
            return ToolOutcome.failure("缺少或空的 'todos' 参数");
        }

        boolean merge = Boolean.TRUE.equals(arguments.get("merge"));

        List<TodoItem> items = new ArrayList<>();
        for (Object obj : todosList) {
            JsonNode node = objectMapper.valueToTree(obj);
            String id = node.path("id").asText("");
            String content = node.path("content").asText("");
            String statusStr = node.path("status").asText("pending");

            if (id.isBlank()) {
                return ToolOutcome.failure("每个待办事项必须有非空的 'id'");
            }
            if (content.isBlank()) {
                return ToolOutcome.failure("每个待办事项必须有非空的 'content'");
            }

            TodoStatus status = parseStatus(statusStr);

            TodoItem item = TodoItem.of(id, content, "medium");
            item.setStatus(status);
            items.add(item);
        }

        // 校验单一焦点
        if (!context.todoStore().validateSingleFocus(items)) {
            return ToolOutcome.failure(
                    "多个待办事项处于 'in_progress' 状态。同一时间只能有一个任务处于进行中状态。");
        }

        List<TodoItem> result;
        if (merge) {
            result = context.todoStore().mergeById(items, state.getTurnCount());
        } else {
            result = context.todoStore().replaceAll(items, state.getTurnCount());
        }

        // 标记 todo 已使用（激活 TodoNavigatorHook）
        state.setTodoBeenUsed(true);

        String progress = cn.kong.eon.store.TodoStore.formatProgress(result);
        log.info("todo_write: {} items (merge={}), {}", result.size(), merge, progress);

        return ToolOutcome.success("待办列表已更新。 " + progress + "\n" + formatTodoList(result));
    }

    private TodoStatus parseStatus(String statusStr) {
        return switch (statusStr.toLowerCase()) {
            case "in_progress" -> TodoStatus.IN_PROGRESS;
            case "completed" -> TodoStatus.COMPLETED;
            case "cancelled" -> TodoStatus.CANCELLED;
            case "blocked" -> TodoStatus.BLOCKED;
            default -> TodoStatus.PENDING;
        };
    }

    private String formatTodoList(List<TodoItem> items) {
        StringBuilder sb = new StringBuilder();
        for (TodoItem t : items) {
            sb.append("  ").append(t.toString()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String summarizeArgs(Map<String, Object> args) {
        Object t = args.get("todos");
        int count = (t instanceof List<?> l) ? l.size() : 0;
        return "{todos: " + count + " items}";
    }

    /**
     * @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。
     */
    @Tool(name = "todo_write", value = {
            "使用此工具为当前会话创建和管理结构化任务列表。",
            "这有助于跟踪进度、组织复杂任务并展示周密性。",
            "注意：除了首次创建待办事项外，不要告诉用户你在更新待办事项，直接执行即可。",
            "在以下情况主动使用：1. 复杂的多步骤任务（3个以上独立步骤）；2. 需要仔细规划的非简单任务；",
            "3. 用户明确要求待办列表；4. 用户提供多个任务；5. 收到新指令后——捕获需求；",
            "6. 完成任务后——标记完成；7. 开始新任务时——标记为进行中。",
            "以下情况跳过：1. 单一的简单任务；2. 琐碎任务；3. 纯对话/信息查询请求。",
            "不要包含操作动作类条目。"
    })
    public String todoWrite(
            @P(name = "todos", description = "要写入的待办事项数组。每个项包含 id（唯一标识符）、content（任务描述）和 status（当前状态：pending/in_progress/completed/cancelled）。") List<TodoItem> todos,
            @P(name = "merge", description = "是否将待办事项与已有待办事项合并。为 true 时按 id 合并；为 false 时全量替换。") boolean merge
    ) {
        return null;
    }

    public static ToolDescriptor descriptor(ObjectMapper objectMapper) {
        return ToolDescriptor.fromAnnotated(new TodoWriteTool(objectMapper), ToolPermission.RESTRICTED_WRITE);
    }
}
