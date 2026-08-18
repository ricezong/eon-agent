package cn.kong.eon.agent.capability.gurad;

import cn.kong.eon.agent.capability.CapabilityModule;
import cn.kong.eon.agent.capability.CapabilityResult;
import cn.kong.eon.agent.capability.Layer;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 循环守卫能力模块（GUARD 层，order=30）。
 * afterModelCall 检测重复调用；afterToolExecution 检测连续失败熔断。
 */
public class LoopGuardCapability implements CapabilityModule {
    private static final Logger log = LoggerFactory.getLogger(LoopGuardCapability.class);

    public static final String CATEGORY_LOOP_DETECTED = "LOOP_DETECTED";

    private final LoopDetector loopDetector;

    public LoopGuardCapability(AgentConfig config) {
        this.loopDetector = new LoopDetector(
                config.getLoopDetect().repeatWarn,
                config.getLoopDetect().repeatStop,
                config.getLoopDetect().noProgressSteps,
                config.getLoopDetect().failureWarn,
                config.getLoopDetect().failureStop);
    }

    @Override public String name() { return "LoopGuard"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public Layer layer() { return Layer.GUARD; }
    @Override public int orderInLayer() { return 30; }

    @Override
    public CapabilityResult afterModelCall(SessionState state, LlmResponse response) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) return CapabilityResult.ok();

        LoopDetector.DetectionResult dr = loopDetector.recordToolCalls(requests);
        if (dr.shouldStop()) {
            log.warn("Loop detected (afterModelCall): {}", dr.message());
            return CapabilityResult.abort(CATEGORY_LOOP_DETECTED, dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
        return CapabilityResult.ok();
    }

    @Override
    public CapabilityResult afterToolExecution(SessionState state, String toolName, boolean success) {
        LoopDetector.DetectionResult dr = loopDetector.recordToolResult(toolName, success);
        if (dr.shouldStop()) {
            log.warn("Loop detected (afterToolExecution): {}", dr.message());
            return CapabilityResult.abort(CATEGORY_LOOP_DETECTED, dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
        return CapabilityResult.ok();
    }
}
