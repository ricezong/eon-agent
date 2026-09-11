package cn.kong.eon.engine.stop;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.event.SessionError;
import cn.kong.eon.event.SessionStatus;
import cn.kong.eon.runtime.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 停止处理器。发出 SessionStatus(terminated) 事件并返回终止输出文本。
 * <p>
 * 无状态：事件通过 {@code r.emit()} 发出（不再自持 emitter），
 * token 与轮次从 {@code r.session()} / {@code r.task()} 取。
 */
@Component
public class StopHandler {
    private static final Logger log = LoggerFactory.getLogger(StopHandler.class);

    private final AgentConfig config;

    public StopHandler(AgentConfig config) {
        this.config = config;
    }

    public AgentConfig config() {
        return config;
    }

    /**
     * 统一终止入口。先发出 SessionError，再发出 SessionStatus(terminated)。
     */
    public String forceTerminate(RunContext r, StopCategory category, String message) {
        log.warn("[停止] {} : {}", category.name(), message);
        r.emit(SessionError.now(message, category.name()));
        r.emit(SessionStatus.terminated(category.name() + ": " + message));
        return "任务终止: " + message + "\n"
                + "消耗: " + r.session().usageAccum().getTotalTokens()
                + " tokens, " + r.task().turnCount() + " 轮\n";
    }
}
