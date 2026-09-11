package cn.kong.eon.tool;

import cn.kong.eon.store.artifact.ArtifactStore;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.todo.TodoStore;

/**
 * 工具执行上下文：一次工具调用的临时参数包。
 * 工具实例是应用级单例，本 record 中的 store/pathResolver 是会话级对象，
 * 严禁把它们存进工具的字段—— execute() 只能在方法内使用。
 */
public record ToolRuntime(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        PathResolver pathResolver,
        /** 当前轮次序号 */
        int turn,
        /** 会话 ID */
        String sessionId
) {
}
