package cn.kong.eon.agent.turn;

import java.time.Instant;

/**
 * Agent 事件基接口。每个事件对应前端一个渲染单元。
 * 引擎按执行阶段发出事件，消费方（日志/SSE/WebSocket）按需渲染。
 */
public interface TurnEvent {

    /** 事件发生时间戳。 */
    Instant timestamp();
}
