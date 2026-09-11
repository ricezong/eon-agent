package cn.kong.eon.engine.stop;

import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.SessionError;
import cn.kong.eon.event.SessionStatus;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.runtime.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 停止处理器。发出 SessionStatus(terminated) 事件并返回终止输出文本。
 */
public class StopHandler {
    private static final Logger log = LoggerFactory.getLogger(StopHandler.class);

    private final AgentConfig config;
    private final Consumer<AgentEvent> emitter;

    public StopHandler(AgentConfig config, Consumer<AgentEvent> emitter) {
        this.config = config;
        this.emitter = emitter;
    }

    public AgentConfig config() {
        return config;
    }

    /**
     * 统一终止入口。先发出 SessionError，再发出 SessionStatus(terminated)。
     */
    public String forceTerminate(SessionState state, StopCategory category, String message) {
        log.warn("[停止] {} : {}", category.name(), message);
        emit(SessionError.now(message, category.name()));
        emit(SessionStatus.terminated(category.name() + ": " + message));
        return "任务终止: " + message + "\n"
                + "消耗: " + state.getUsageAccum().getTotalTokens()
                + " tokens, " + state.getTurnCount() + " 轮\n";
    }

    private void emit(AgentEvent event) {
        if (emitter != null) {
            emitter.accept(event);
        }
    }
}
