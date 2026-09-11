package cn.kong.eon.agent.hook.postmodel;

import cn.kong.eon.agent.hook.Hook;
import cn.kong.eon.agent.hook.HookResult;
import cn.kong.eon.agent.stop.StopCategory;
import cn.kong.eon.agent.guard.ToolCircuitBreaker;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.session.SessionState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环检测（PostModel, order=30）。
 * 检测同一轮内重复的工具调用：同工具同参数重复达到 stop 阈值时直接终止，
 * 达到 warn 阈值时注入 nudge。已熔断的工具跳过检测——执行层会直接拦截。
 */
public class LoopDetectHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(LoopDetectHook.class);

    private static final String STOP_MSG = "工具 %s 以相同参数调用 %s 次";
    private static final String WARN_MSG = "工具 %s 已重复调用 %s 次，请考虑换参数或换工具";

    private final int warnThreshold;
    private final int stopThreshold;
    private final ToolCircuitBreaker tracker;

    /** 指纹 → 调用次数，同一轮内累计 */
    private final Map<String, Integer> callFingerprintCount = new HashMap<>();

    public LoopDetectHook(AgentConfig.LoopDetectConfig cfg, ToolCircuitBreaker tracker) {
        this.warnThreshold = cfg.getRepeatWarn();
        this.stopThreshold = cfg.getRepeatStop();
        this.tracker = tracker;
    }

    @Override
    public String name() {
        return "LoopDetect";
    }

    @Override
    public boolean active(SessionState state) {
        return true;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public HookResult afterModelCall(SessionState state) {
        List<ToolExecutionRequest> requests = state.getPendingToolCalls();
        if (requests == null || requests.isEmpty()) {
            return HookResult.ok();
        }

        for (ToolExecutionRequest req : requests) {
            if (tracker.isTripped(req.name())) {
                continue;
            }

            String fingerprint = req.name() + "|" + (req.arguments() != null ? req.arguments() : "");
            int count = callFingerprintCount.getOrDefault(fingerprint, 0) + 1;
            callFingerprintCount.put(fingerprint, count);

            if (count >= stopThreshold) {
                log.warn("[LoopDetect] 死循环: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                String msg = String.format(STOP_MSG, req.name(), count);
                return HookResult.stop(StopCategory.LOOP_DETECTED, StopCategory.LOOP_DETECTED.format(msg));
            }
            if (count >= warnThreshold) {
                log.info("[LoopDetect] 告警 - 工具 '{}' 重复 {} 次", req.name(), count);
                state.addNudge(String.format(WARN_MSG, req.name(), count));
                return HookResult.skip();
            }
        }
        return HookResult.ok();
    }

    /** 清空全部指纹，在每个任务开始时调用。 */
    public void reset() {
        callFingerprintCount.clear();
    }
}
