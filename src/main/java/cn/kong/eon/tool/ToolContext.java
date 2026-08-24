package cn.kong.eon.tool;

import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.TodoStore;

/** 工具执行上下文，为工具提供运行时依赖。 */
public record ToolContext(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        JsonlStore jsonlStore,
        CheckpointStore checkpointStore,
        String workDir,
        InteractionCallback interactionCallback
) {
    /** 无交互回调（CLI 模式）。 */
    public ToolContext(TodoStore todoStore, ArtifactStore artifactStore,
                      MemoryStore memoryStore, JsonlStore jsonlStore,
                      CheckpointStore checkpointStore, String workDir) {
        this(todoStore, artifactStore, memoryStore, jsonlStore, checkpointStore, workDir, null);
    }
}
