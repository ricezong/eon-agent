package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.MemoryStore.MemoryItem;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;
import cn.kong.eon.tool.ToolOutcome;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * update_memory 工具：创建/更新/删除跨会话记忆。
 * 语义约束：
 * - 反驳 → delete（而非 update）
 * - 补充 → update
 * - 任务性信息禁止入库
 * - 未明确要求不 create
 */
public class UpdateMemoryTool implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(UpdateMemoryTool.class);

    /** @Tool 注解方法：供 ToolSpecifications 扫描生成 Schema。 */
    @Tool(name = "update_memory", value = {
            "在持久化知识库中创建、更新或删除记忆，供 AI 将来参考。如果用户补充了已有记忆，必须使用此工具的 'update' 操作。",
            "如果用户反驳了已有记忆，必须使用此工具的 'delete' 操作，而非 'update' 或 'create'。",
            "要更新或删除已有记忆时，必须提供 existing_knowledge_id 参数。",
            "如果用户要求记住某事、保存某事或创建记忆，必须使用此工具的 'create' 操作。",
            "除非用户明确要求记住或保存某事，不要使用 'create' 操作调用此工具。"
    })
    public String updateMemory(
            @P(name = "action", description = "对知识库执行的操作。默认为 'create'。枚举值：\"create\"、\"update\"、\"delete\"。", required = false) String action,
            @P(name = "existing_knowledge_id", description = "当 action 为 'update' 或 'delete' 时必填。要更新或删除的已有记忆的 ID。", required = false) String existing_knowledge_id,
            @P(name = "knowledge_to_store", description = "要存储的具体记忆内容。不超过一段话。当 action 为 'create' 或 'update' 时必填。", required = false) String knowledge_to_store,
            @P(name = "title", description = "要存储的记忆的标题。当 action 为 'create' 或 'update' 时必填。", required = false) String title
    ) {
        return null;
    }

    public static ToolDescriptor descriptor() {
        return ToolDescriptor.fromAnnotated(new UpdateMemoryTool(), ToolPermission.READONLY);
    }

    @Override
    public ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        MemoryStore memoryStore = context.memoryStore();
        String action = (String) arguments.getOrDefault("action", "create");

        try {
            return switch (action) {
                case "create" -> {
                    String title = (String) arguments.get("title");
                    String content = (String) arguments.get("knowledge_to_store");
                    if (title == null || title.isBlank()) {
                        yield ToolOutcome.failure("create 操作需要 'title' 参数");
                    }
                    if (content == null || content.isBlank()) {
                        yield ToolOutcome.failure("create 操作需要 'knowledge_to_store' 参数");
                    }
                    MemoryItem item = memoryStore.create(title, content);
                    log.info("Memory created: {} - {}", item.id, title);
                    yield ToolOutcome.success("记忆创建成功。\nID: " + item.id + "\n标题: " + title);
                }
                case "update" -> {
                    String id = (String) arguments.get("existing_knowledge_id");
                    if (id == null || id.isBlank()) {
                        yield ToolOutcome.failure("update 操作需要 'existing_knowledge_id' 参数");
                    }
                    String title = (String) arguments.get("title");
                    String content = (String) arguments.get("knowledge_to_store");
                    if ((title == null || title.isBlank()) && (content == null || content.isBlank())) {
                        yield ToolOutcome.failure("update 操作至少需要提供 'title' 或 'knowledge_to_store' 中的一个");
                    }
                    MemoryItem item = memoryStore.update(id, title, content);
                    log.info("Memory updated: {}", id);
                    yield ToolOutcome.success("记忆更新成功。\nID: " + id + "\n标题: " + item.title);
                }
                case "delete" -> {
                    String id = (String) arguments.get("existing_knowledge_id");
                    if (id == null || id.isBlank()) {
                        yield ToolOutcome.failure("delete 操作需要 'existing_knowledge_id' 参数");
                    }
                    boolean deleted = memoryStore.delete(id);
                    if (deleted) {
                        log.info("Memory deleted: {}", id);
                        yield ToolOutcome.success("记忆删除成功。\nID: " + id);
                    } else {
                        yield ToolOutcome.failure("记忆不存在或无法删除: " + id);
                    }
                }
                default -> ToolOutcome.failure("未知操作: " + action + "。有效操作：create、update、delete");
            };
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failure(e.getMessage());
        } catch (Exception e) {
            log.error("update_memory failed: {}", e.getMessage(), e);
            return ToolOutcome.failure("记忆操作失败: " + e.getMessage());
        }
    }
}
