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
 * <p>
 * 原 {@code interactionCallback} 字段<b>已删除</b>：调用方始终传 {@code null}，
 * 全项目无任何实现，属于假扩展点。保留它只会让 AskQuestionTool 在运行时才暴露"不可用"，
 * 删除后该约束前移到编译期。交互能力的正确接入口是 {@link InteractionCallback}
 * 接口本身——将来真正接入时，在此 record 重新加回字段即可。
 * <p>
 * <b>使用约束（重要）</b>：内置工具实例是<b>应用级单例</b>（在 {@code AgentBeans} 里一次性
 * 注册进单例 {@code ToolService}），而本 record 里的 {@code todoStore} / {@code artifactStore} /
 * {@code pathResolver} 是<b>会话级</b>对象（每个 {@code SessionScope} 各一份）。
 * 因此工具的 {@code execute()} 只能在方法内使用这些引用，<b>严禁</b>把它们存进工具自己的字段——
 * 一旦留存，第一个会话的对象就会被后续所有会话复用，且指向可能已关闭、甚至已删除的会话目录。
 * 这类缺陷单会话测试完全正常，只在多会话并发时才暴露，故在此显式写明。
 */
public record ToolRuntime(
        TodoStore todoStore,
        ArtifactStore artifactStore,
        MemoryStore memoryStore,
        PathResolver pathResolver,
        /** 当前轮次序号（原 state.getTurnCount()） */
        int turn,
        /** 会话 ID（原 state.getSessionId()） */
        String sessionId
) {
}
