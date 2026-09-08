package cn.kong.eon.agent.support;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停止处理器。记录日志并返回终止输出文本。
 */
public class StopHandler {
    private static final Logger log = LoggerFactory.getLogger(StopHandler.class);

    private final AgentConfig config;
    private final TurnLogger logger;

    public StopHandler(AgentConfig config, TurnLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    public AgentConfig config() {
        return config;
    }

    /**
     * 统一终止入口。记录日志，拼终止原因 + 消耗统计。
     *
     * @param message 已由 {@link StopCategory#format} 生成的终止描述
     */
    public String forceTerminate(SessionState state, StopCategory category, String message) {
        log.warn("[停止] {} : {}", category.name(), message);
        logger.stopForced(category.name(), state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return "任务终止: " + message + "\n"
                + "消耗: " + state.getUsageAccum().getTotalTokens()
                + " tokens, " + state.getTurnCount() + " 轮\n";
    }
}
