package cn.kong.eon.agent.stop;

import cn.kong.eon.agent.turn.TurnEvent;
import cn.kong.eon.agent.turn.event.TaskStopped;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 停止处理器。发出 TaskStopped 事件并返回终止输出文本。
 */
public class StopHandler {
    private static final Logger log = LoggerFactory.getLogger(StopHandler.class);

    private final AgentConfig config;
    private final Consumer<TurnEvent> emitter;

    public StopHandler(AgentConfig config, Consumer<TurnEvent> emitter) {
        this.config = config;
        this.emitter = emitter;
    }

    public AgentConfig config() {
        return config;
    }

    /**
     * 统一终止入口。发出 TaskStopped 事件，拼终止原因 + 消耗统计。
     *
     * @param message 已由 {@link StopCategory#format} 生成的终止描述
     */
    public String forceTerminate(SessionState state, StopCategory category, String message) {
        log.warn("[停止] {} : {}", category.name(), message);
        emit(TaskStopped.now(category.name(), message, state.getTurnCount(),
                state.getUsageAccum().getTotalTokens()));
        return "任务终止: " + message + "\n"
                + "消耗: " + state.getUsageAccum().getTotalTokens()
                + " tokens, " + state.getTurnCount() + " 轮\n";
    }

    private void emit(TurnEvent event) {
        if (emitter != null) {
            emitter.accept(event);
        }
    }
}
