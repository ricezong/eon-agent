package cn.kong.eon.tool;

import cn.kong.eon.store.artifact.ToolResultArtifactStore;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.snapshot.SessionSnapshotStore;
import cn.kong.eon.store.todo.TodoStore;

/**
 * 工具执行上下文，为工具提供运行时依赖。
 */
public record ToolContext(
        TodoStore todoStore,
        ToolResultArtifactStore toolResultStore,
        MemoryStore memoryStore,
        TranscriptLedger transcriptLedger,
        SessionSnapshotStore snapshotStore,
        PathResolver pathResolver,
        InteractionCallback interactionCallback
) {
}
