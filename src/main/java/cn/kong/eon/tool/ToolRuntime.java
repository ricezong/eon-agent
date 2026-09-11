package cn.kong.eon.tool;

import cn.kong.eon.store.artifact.ArtifactStore;
import cn.kong.eon.store.memory.MemoryStore;
import cn.kong.eon.store.todo.TodoStore;

/**
 * 工具执行上下文：一次工具调用的临时参数包。
 * <p>
 * 已从"会话级常驻对象"降级为"一次调用的参数包"：删掉无人使用的
 * {@code transcriptLedger} 与 {@code snapshotStore}（纯越权暴露路径），
 * 补上工具真正需要的两个<b>只读</b>值 {@code turn} 与 {@code sessionId}。
 * <p>
 * 工具的 {@code runtime.xxx()} 调用形式保持不变，仅这两个值改由调用方从
 * {@code RunContext} 的 {@code turn()} / {@code session()} 现取现传。
 */
public record ToolRuntime(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        PathResolver pathResolver,
        InteractionCallback interactionCallback,
        /** 当前轮次序号（原 state.getTurnCount()） */
        int turn,
        /** 会话 ID（原 state.getSessionId()） */
        String sessionId
) {
}
