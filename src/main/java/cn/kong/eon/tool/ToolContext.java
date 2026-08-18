package cn.kong.eon.tool;

import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.CheckpointStore;
import cn.kong.eon.store.InsightsStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.TodoStore;

/** 工具执行上下文，为工具提供运行时依赖（store 访问能力）。 */
public record ToolContext(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        InsightsStore insightsStore,
        JsonlStore jsonlStore,
        CheckpointStore checkpointStore,
        String downloadDir,
        String workDir
) {}
