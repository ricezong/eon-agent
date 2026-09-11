package cn.kong.eon.engine.hook.postmodel;

import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookResult;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.runtime.RunContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 循环检测（PostModel, order=30）。
 * 检测同一任务内重复的工具调用：同工具同参数重复达到 stop 阈值时直接终止，
 * 达到 warn 阈值时注入 nudge。已熔断的工具跳过检测——执行层会直接拦截。
 * <p>
 * 无状态：计数表外置到 {@code r.task().loopDetector()}（每次 run 独立实例），
 * 这是 Hook 能安全单例化的关键——原先的私有 HashMap 在多会话并发时会互相污染。
 */
@Component
public class LoopDetectHook implements Hook.PostModelHook {
    private static final Logger log = LoggerFactory.getLogger(LoopDetectHook.class);

    private static final String STOP_MSG = "工具 %s 以相同参数调用 %s 次";
    private static final String WARN_MSG = "工具 %s 已重复调用 %s 次，请考虑换参数或换工具";

    @Override
    public String name() {
        return "LoopDetect";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public HookResult afterModelCall(RunContext r) {
        List<ToolExecutionRequest> requests = r.turn().pendingToolCalls();
        if (requests == null || requests.isEmpty()) {
            return HookResult.ok();
        }

        for (ToolExecutionRequest req : requests) {
            if (r.session().circuitBreaker().isTripped(req.name())) {
                continue;
            }

            String fingerprint = req.name() + "|" + (req.arguments() != null ? req.arguments() : "");
            int count = r.task().loopDetector().record(fingerprint);

            if (count >= r.task().loopDetector().stopThreshold()) {
                log.warn("[LoopDetect] 死循环: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                String msg = String.format(STOP_MSG, req.name(), count);
                return HookResult.stop(StopCategory.LOOP_DETECTED, StopCategory.LOOP_DETECTED.format(msg));
            }
            if (count >= r.task().loopDetector().warnThreshold()) {
                log.info("[LoopDetect] 告警 - 工具 '{}' 重复 {} 次", req.name(), count);
                r.task().addNudge(String.format(WARN_MSG, req.name(), count));
                return HookResult.skip();
            }
        }
        return HookResult.ok();
    }
}
