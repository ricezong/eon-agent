package cn.kong.eon.agent.support;

import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.agent.support.HookDispatcher.FireResult;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmStalledException;
import cn.kong.eon.model.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 优雅停止状态机。管理 grace step 消耗、硬终止判定、停止请求处理和终止输出格式化。
 * 依赖 {@link MessageFinalizer} 处理 stop 期间的 pending 消息回填。
 */
public class StopStateMachine {
    private static final Logger log = LoggerFactory.getLogger(StopStateMachine.class);

    private final AgentConfig config;
    private final TurnLogger logger;
    private final MessageFinalizer finalizer;

    public StopStateMachine(AgentConfig config, TurnLogger logger, MessageFinalizer finalizer) {
        this.config = config;
        this.logger = logger;
        this.finalizer = finalizer;
    }

    /**
     * 消耗一个 grace step，返回 Exit 表示硬终止，Continue 表示继续循环。
     */
    public TurnAction consumeGraceStep(TurnRecord rec, SessionState state, String reason) {
        boolean hasMore = state.getStopState().consumeGraceStep();
        logger.graceConsumed(rec, reason, state.getStopState().getRemainingGraceSteps());
        if (!hasMore) {
            return new TurnAction.Exit(forceTerminate(state, state.getStopState().getReason()));
        }
        return new TurnAction.Continue();
    }

    /**
     * 处理 Agent 主循环中的异常。LLM 不可用时直接硬终止；其他异常尝试优雅停止。
     */
    public TurnAction handleLoopException(SessionState state, Exception e) {
        log.error("Agent loop unexpected error: {}", e.getMessage(), e);
        if (e instanceof LlmStalledException) {
            return new TurnAction.Exit(forceTerminate(state, new StopReason(
                    StopCategory.UNEXPECTED_ERROR, "LLM 调用连续失败，模型不可用", 0)));
        }
        // 其他异常：尝试优雅停止
        FireResult sr = handleStop(state, new StopReason(
                StopCategory.UNEXPECTED_ERROR, e.getMessage(), config.getBudget().getGraceSteps()));
        return sr instanceof FireResult.Exit exit ? new TurnAction.Exit(exit.output()) : new TurnAction.Continue();
    }

    /**
     * maxSteps 达到上限时的停止处理。
     */
    public TurnAction handleMaxSteps(SessionState state) {
        log.warn("[STOP] max steps reached: {}", config.getLoop().getMaxSteps());
        StopReason reason = new StopReason(
                StopCategory.MAX_STEPS_REACHED,
                "达到最大步数限制 (" + config.getLoop().getMaxSteps() + ")",
                config.getBudget().getGraceSteps());
        FireResult sr = handleStop(state, reason);
        return sr instanceof FireResult.Exit exit ? new TurnAction.Exit(exit.output())
                : new TurnAction.Exit(forceTerminate(state, reason));
    }

    /**
     * 处理 stop 请求（无 TurnRecord，用于 maxSteps/异常等 turn 之外的场景）。
     */
    public FireResult handleStop(SessionState state, StopReason reason) {
        return handleStop(null, state, reason);
    }

    /**
     * 处理 stop 请求：注入收尾 nudge，进入 grace period。
     * graceSteps=0 直接硬终止；已在 stop 中则追加 nudge 提醒，不重置 grace。
     *
     * @param rec 当前 TurnRecord，null 表示在 turn 之外（maxSteps/异常等场景）
     */
    public FireResult handleStop(TurnRecord rec, SessionState state, StopReason reason) {
        if (reason.getGraceSteps() <= 0) {
            return new FireResult.Exit(forceTerminate(state, reason));
        }

        if (!state.isStopRequested()) {
            state.getStopState().request(reason);
            state.addNudge(reason.toNudgeText());
            if (rec != null) {
                logger.stopRequested(rec, reason.getCategory(), reason.getMessage(), reason.getGraceSteps());
            } else {
                log.warn("[STOP] requested: {} | msg: {} | grace: {}",
                        reason.getCategory(), reason.getMessage(), reason.getGraceSteps());
            }
            finalizer.finalizeIfPending(rec, state);
            return new FireResult.Continue();
        }

        // 已在 stop 中，追加提醒
        if (state.getStopState().getRemainingGraceSteps() <= 0) {
            return new FireResult.Exit(forceTerminate(state, reason));
        }
        state.addNudge(reason.toNudgeText());
        if (rec != null) {
            logger.stopEscalated(rec, reason.getCategory(), reason.getMessage());
        } else {
            log.warn("[STOP] escalated: {} | msg: {}", reason.getCategory(), reason.getMessage());
        }
        finalizer.finalizeIfPending(rec, state);
        return new FireResult.Continue();
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
