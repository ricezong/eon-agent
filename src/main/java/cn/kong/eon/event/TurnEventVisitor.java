package cn.kong.eon.event;

/**
 * 事件访问者接口。新增事件类型时增加 visit 方法并在事件类中实现 accept。
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

    /** 未知事件类型兜底。 */
    T visitUnknown(TurnEvent e);
}
