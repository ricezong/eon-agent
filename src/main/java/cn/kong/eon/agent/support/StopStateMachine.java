package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmStalledException;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停止状态机。处理 maxSteps 超限、循环异常、Hook stop 三类终止场景，统一执行硬终止。
 */
public class StopStateMachine {
    private static final Logger log = LoggerFactory.getLogger(StopStateMachine.class);

    private final AgentConfig config;
    private final TurnLogger logger;

    public StopStateMachine(AgentConfig config, TurnLogger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** maxSteps 达到上限时的硬终止，返回终止输出文本。 */
    public String handleMaxSteps(SessionState state) {
        log.warn("[停止] 达到最大步数: {}", config.getLoop().getMaxSteps());
        return forceTerminate(state, new StopReason(
                StopCategory.MAX_STEPS_REACHED,
                "达到最大步数限制 (" + config.getLoop().getMaxSteps() + ")"));
    }

    /** 处理 Agent 主循环中的异常，返回终止输出文本。 */
    public String handleLoopException(SessionState state, Exception e) {
        log.error("Agent 循环异常: {}", e.getMessage(), e);
        if (e instanceof LlmStalledException) {
            return forceTerminate(state, new StopReason(
                    StopCategory.UNEXPECTED_ERROR, "LLM 调用连续失败，模型不可用"));
        }
        return forceTerminate(state, new StopReason(
                StopCategory.UNEXPECTED_ERROR, e.getMessage()));
    }

    /** 硬终止：记录日志并返回终止输出。 */
    public String forceTerminate(SessionState state, StopReason reason) {
        logger.stopForced(reason.getCategory().name(), state.getTurnCount(), state.getUsageAccum().getTotalTokens());
        return formatTerminationOutput(state, reason);
    }

    /** 拼接硬终止输出：终止原因 + 消耗统计。 */
    private String formatTerminationOutput(SessionState state, StopReason reason) {
        return "任务终止: " + reason.getCategory().getDisplayName() + "\n"
                + "原因: " + reason.getMessage() + "\n"
                + "消耗: " + state.getUsageAccum().getTotalTokens()
                + " tokens, " + state.getTurnCount() + " 轮\n";
    }
}
