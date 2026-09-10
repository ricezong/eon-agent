package cn.kong.eon.agent.event;

/**
 * 事件访问者接口。按事件具体类型分发，避免 instanceof 链。
 * 每新增事件类型只需在此接口增加一个 visit 方法并在事件类中实现 accept。
 *
 * @param <T> 返回值类型
 */
public interface TurnEventVisitor<T> {

    T visitDelta(AgentDelta e);

    T visitThinking(AgentThinking e);

    T visitMessage(AgentMessage e);

    T visitToolUse(AgentToolUse e);

    T visitToolResult(AgentToolResult e);

    T visitUsage(SessionUsage e);

    T visitStatus(SessionStatus e);

    T visitError(SessionError e);

    /** 未知事件类型的兜底处理。 */
    T visitUnknown(TurnEvent e);
}
