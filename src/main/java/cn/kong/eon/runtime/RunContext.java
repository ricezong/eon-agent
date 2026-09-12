package cn.kong.eon.runtime;

import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.AgentEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 运行时上下文：一次 run 一个实例。
 * 三层作用域全从这里取：session()（会话级）/ task()（任务级）/ turn()（轮次级）。
 */
public final class RunContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunContext.class);

    private final SessionScope session;
    private final TaskScope task;
    private TurnScope turn;
    private final Consumer<AgentEvent> emitter;

    public RunContext(SessionScope session,
                      TaskScope task,
                      List<AgentEventListener> listeners) {
        this.session = session;
        this.task = task;
        // 占位轮，引擎每轮调用 nextTurn() 后替换
        this.turn = new TurnScope(0);
        this.emitter = buildEmitter(listeners);
        session.bindRun(this);
    }

    public SessionScope session() {
        return session;
    }

    public TaskScope task() {
        return task;
    }

    public TurnScope turn() {
        return turn;
    }

    /** 开启新一轮：递增任务轮次并整体替换 TurnScope。 */
    public TurnScope nextTurn() {
        this.turn = new TurnScope(task.incrementTurn());
        return this.turn;
    }

    /** 发出一个 Agent 事件。 */
    public void emit(AgentEvent event) {
        emitter.accept(event);
    }

    /** 任务收尾：保存终态快照。不释放会话状态机（由 SessionRegistry 负责）。 */
    @Override
    public void close() {
        try {
            session.saveSnapshot();
        } catch (Exception e) {
            log.warn("会话 {} 保存终态快照失败", session.sessionId(), e);
        }
    }

    private static Consumer<AgentEvent> buildEmitter(List<AgentEventListener> listeners) {
        List<AgentEventListener> copy = listeners == null ? List.of() : List.copyOf(listeners);
        return event -> {
            for (AgentEventListener l : copy) {
                try {
                    l.onEvent(event);
                } catch (Exception e) {
                    log.warn("事件监听器异常: {}", e.getMessage(), e);
                }
            }
        };
    }
}
