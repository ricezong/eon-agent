package cn.kong.eon.agent.capability;

import cn.kong.eon.agent.context.ContextBuilder;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 循环守卫能力模块。
 *
 * 始终激活。职责：
 * - afterModelCall：检测重复调用（同一工具同一参数）
 * - afterToolExecution：检测连续失败熔断
 */
public class LoopGuard implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(LoopGuard.class);

    private final LoopDetector loopDetector;

    public LoopGuard(AgentConfig config) {
        this.loopDetector = new LoopDetector(
                config.getLoopDetect().repeatWarn,
                config.getLoopDetect().repeatStop,
                config.getLoopDetect().noProgressSteps,
                config.getLoopDetect().failureWarn,
                config.getLoopDetect().failureStop);
    }

    @Override
    public String name() { return "LoopGuard"; }

    @Override
    public boolean isActive(SessionState state) { return true; }

    @Override
    public void afterModelCall(SessionState state, LlmResponse response) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) return;

        LoopDetector.DetectionResult dr = loopDetector.recordToolCalls(requests);
        if (dr.shouldStop()) {
            throw new LoopDetectedException(dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
    }

    @Override
    public void afterToolExecution(SessionState state, String toolName, boolean success) {
        LoopDetector.DetectionResult dr = loopDetector.recordToolResult(toolName, success);
        if (dr.shouldStop()) {
            throw new LoopDetectedException(dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
    }

    public static class LoopDetectedException extends RuntimeException {
        public LoopDetectedException(String message) {
            super(message);
        }
    }
}
