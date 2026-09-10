package cn.kong.eon.tool;

import cn.kong.eon.store.artifact.ToolResultStore;
import cn.kong.eon.store.ledger.JsonlStore;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoStore;

/**
 * 工具执行上下文，为工具提供运行时依赖。
 */
public record ToolContext(
        TodoStore todoStore,
        ToolResultStore toolResultStore,
        MemoryStore memoryStore,
        JsonlStore jsonlStore,
        SessionSnapshotStore snapshotStore,
        PathResolver pathResolver,
        InteractionCallback interactionCallback
) {

    /** 返回替换了 interactionCallback 的新实例。 */
    public ToolContext withInteractionCallback(InteractionCallback callback) {
        return new ToolContext(todoStore, toolResultStore, memoryStore,
                jsonlStore, snapshotStore, pathResolver, callback);
    }
}
