package cn.kong.eon.tool;

import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.TodoStore;

/**
 * 工具执行上下文，为工具提供运行时依赖。
 *
 * @param todoStore           待办列表存储
 * @param artifactStore       大内容落盘存储
 * @param memoryStore         跨会话记忆存储
 * @param jsonlStore          对话历史存储
 * @param checkpointStore     检查点存储
 * @param pathResolver        路径解析器（含沙箱校验）
 * @param interactionCallback 用户交互回调（API 模式），null 表示 CLI 模式
 */
public record ToolContext(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        JsonlStore jsonlStore,
        CheckpointStore checkpointStore,
        PathResolver pathResolver,
        InteractionCallback interactionCallback
) {
    /** CLI 模式构造（无交互回调）。 */
    public ToolContext(TodoStore todoStore, ArtifactStore artifactStore,
                       MemoryStore memoryStore, JsonlStore jsonlStore,
                       CheckpointStore checkpointStore, PathResolver pathResolver) {
        this(todoStore, artifactStore, memoryStore, jsonlStore, checkpointStore, pathResolver, null);
    }
}
