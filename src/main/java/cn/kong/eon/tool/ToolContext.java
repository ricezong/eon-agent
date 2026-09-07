package cn.kong.eon.tool;

import cn.kong.eon.store.ArtifactStore;
import cn.kong.eon.store.JsonlStore;
import cn.kong.eon.store.MemoryStore;
import cn.kong.eon.store.SessionSnapshotStore;
import cn.kong.eon.store.TodoStore;

/**
 * 工具执行上下文，为工具提供运行时依赖。
 *
 * @param todoStore           待办列表存储
 * @param artifactStore       大内容落盘存储
 * @param memoryStore         跨会话记忆存储
 * @param jsonlStore          对话历史存储
 * @param snapshotStore       会话快照存储
 * @param pathResolver        路径解析器（含沙箱校验）
 * @param interactionCallback 用户交互回调，两种运行模式都传：CLI 下是 {@code CliInteractionCallback}，
 *                            API 下是 HTTP 实现；AskQuestion 工具据此向用户发问
 */
public record ToolContext(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        JsonlStore jsonlStore,
        SessionSnapshotStore snapshotStore,
        PathResolver pathResolver,
        InteractionCallback interactionCallback
) {
}
