package cn.kong.eon.event;

import java.time.Instant;

/**
 * Agent 事件基接口。每个事件对应前端一个渲染单元，也是 SSE 推送的一个数据帧。
 * 引擎按执行阶段发出事件，消费方（SSE/日志）按需渲染。
 */
public interface AgentEvent {

    /** SSE 事件名，用作 {@code event: xxx} 行的 xxx。 */
    String type();

    /** 事件发生时间戳。 */
    Instant timestamp();

    /** 访问者模式分发。 */
    <T> T accept(AgentEventVisitor<T> visitor);
}
