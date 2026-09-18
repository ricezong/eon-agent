package cn.kong.eon.event;

/**
 * 事件监听器。消费方实现此接口，按事件类型自行渲染。
 */
@FunctionalInterface
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
