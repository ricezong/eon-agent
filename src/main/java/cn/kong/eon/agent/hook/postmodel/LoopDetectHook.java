package cn.kong.eon.agent.hook.postmodel;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.hook.StopCategory;
import cn.kong.eon.agent.hook.StopReason;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.agent.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 循环检测（PostModel, order=30）。
 * 检测重复工具调用模式：同一参数重复达到 stop 阈值时请求优雅停止。
 * 熔断工具的检测在此阶段只返回 WARN（nudge 提示），不阻止其他工具执行。
 */
public class LoopDetectHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(LoopDetectHook.class);

    private final int stopGraceSteps;
    private final LoopDetector loopDetector;

    public LoopDetectHook(LoopDetector loopDetector) {
        this(loopDetector, 2);
    }

    public LoopDetectHook(LoopDetector loopDetector, int stopGraceSteps) {
        this.loopDetector = loopDetector;
        this.stopGraceSteps = stopGraceSteps;
    }

    @Override
    public String name() {
        return "LoopDetect";
    }

    @Override
    public boolean isActive(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public HookResult afterModelCall(SessionState state, LlmResponse response) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) return HookResult.ok();

        LoopDetector.DetectionResult dr = loopDetector.recordToolCalls(requests);
        if (dr.shouldStop()) {
            log.warn("[循环检测] 停止 - {}", dr.message());
            StopReason reason = new StopReason(
                    StopCategory.LOOP_DETECTED,
                    dr.message(),
                    stopGraceSteps);
            return HookResult.stop(reason);
        }
        if (dr.shouldWarn()) {
            log.info("[循环检测] 告警 - {}", dr.message());
            state.getPendingNudges().add(dr.message());
        }
        return HookResult.ok();
    }
}
