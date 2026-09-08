package cn.kong.eon.agent.turn;

/**
 * 事件监听器。消费方实现此接口，按事件类型自行渲染。
 * <p>
 * 引擎持有一组 Listener，每发出一个事件就遍历通知全部 Listener。
 */
@FunctionalInterface
public interface TurnListener {
    void onEvent(TurnEvent event);
}
