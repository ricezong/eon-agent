package cn.kong.eon.agent.hook.postmodel;

import cn.kong.eon.agent.hook.AbortCategory;
import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.llm.LlmResponse;
import cn.kong.eon.loop.LoopDetector;
import cn.kong.eon.model.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 循环检测（模型调用后阶段，order=30）。
 * 检测重复工具调用模式。
 */
public class LoopDetectHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(LoopDetectHook.class);

    private final LoopDetector loopDetector;

    public LoopDetectHook(LoopDetector loopDetector) {
        this.loopDetector = loopDetector;
    }

    @Override public String name() { return "LoopDetect"; }
    @Override public boolean isActive(SessionState state) { return true; }
    @Override public int order() { return 30; }

    @Override
    public HookResult afterModelCall(SessionState state, LlmResponse response) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) return HookResult.ok();

        LoopDetector.DetectionResult dr = loopDetector.recordToolCalls(requests);
        if (dr.shouldStop()) {
            log.warn("Loop detected (afterModelCall): {}", dr.message());
            return HookResult.abort(AbortCategory.LOOP_DETECTED, dr.message());
        }
        if (dr.shouldWarn()) {
            state.getPendingNudges().add(dr.message());
        }
        return HookResult.ok();
    }
}
