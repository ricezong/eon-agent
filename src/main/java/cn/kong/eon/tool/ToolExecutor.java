package cn.kong.eon.tool;

import cn.kong.eon.model.SessionState;

import java.util.Map;

/**
 * 工具执行器接口。接收参数与运行时上下文，返回执行结果。
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * @param arguments 模型传入的参数（含 reason 字段）
     */
    ToolOutcome execute(Map<String, Object> arguments, SessionState state, ToolContext context);

    /**
     * 提取工具调用的关键参数摘要，用于日志展示。
     * 默认实现返回参数 Map 的 toString 截断版本，工具可覆写以提供更有意义的摘要。
     */
    default String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(none)";
        String s = args.toString();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    /**
     * 本工具是否会把调用参数<b>完整持久化</b>到磁盘。
     * <p>
     * 返回 true 时，上下文层会把该工具的 arguments 块标记为可无损卸载
     * （{@code Retention.OFFLOADABLE}）：既然磁盘上已有逐字节相同的副本，
     * 历史里那份就是纯冗余，替换为"参数骨架 + 路径引用"不损失任何信息。
     * <p>
     * 典型场景是 write：一次写入 25K 字符的 HTML，参数和磁盘文件完全重复。
     * 业界通用规则是"只截输出不截输入"，理由是 arguments 由模型生成、通常很短——
     * 但这条规则在 write / edit 类工具上不成立。
     * <p>
     * 默认 false（保守）。只有确实把入参原样落盘的工具才应覆写为 true。
     */
    default boolean persistsArguments() {
        return false;
    }

    /**
     * 释放工具持有的资源（如 Scanner、文件句柄等）。
     * 默认空操作，由持有资源的工具覆写。
     * 在会话销毁时由 ToolRegistry.closeAll() 统一调用。
     */
    default void close() {
    }
}
