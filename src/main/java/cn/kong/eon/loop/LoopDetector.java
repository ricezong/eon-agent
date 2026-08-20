package cn.kong.eon.loop;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 死循环检测器。三种检测：
 *   ① 重复调用——同一工具同一参数连续调用超过阈值
 *   ② 无进展——连续 N 步 Todo 状态未变化
 *   ③ 连续失败熔断——工具连续失败超过阈值（单工具 + 全局）
 */
public class LoopDetector {
    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    private final int repeatWarn;
    private final int repeatStop;
    private final int noProgressSteps;
    private final int failureWarnThreshold;
    private final int failureStopThreshold;

    private final Map<String, Integer> callFingerprintCount = new HashMap<>();
    private final Deque<String> todoSnapshots = new ArrayDeque<>();
    private int stepsWithoutProgress = 0;

    private int consecutiveFailures = 0;
    private final Map<String, Integer> toolFailureCount = new HashMap<>();
    private final Set<String> trippedTools = new HashSet<>();

    public LoopDetector(int repeatWarn, int repeatStop, int noProgressSteps) {
        this(repeatWarn, repeatStop, noProgressSteps, 3, 5);
    }

    public LoopDetector(int repeatWarn, int repeatStop, int noProgressSteps,
                        int failureWarnThreshold, int failureStopThreshold) {
        this.repeatWarn = repeatWarn;
        this.repeatStop = repeatStop;
        this.noProgressSteps = noProgressSteps;
        this.failureWarnThreshold = failureWarnThreshold;
        this.failureStopThreshold = failureStopThreshold;
    }

    /** 记录工具调用，检测重复调用和已熔断工具。 */
    public DetectionResult recordToolCalls(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return DetectionResult.ok();
        }

        for (ToolExecutionRequest req : requests) {
            if (trippedTools.contains(req.name())) {
                log.warn("[LoopDetector] circuit breaker tripped: tool '{}' is blocked", req.name());
                return DetectionResult.stop("工具 " + req.name() + " 已被熔断（连续失败过多），" +
                        "请标记 blocked 或调整计划，不要再调用此工具");
            }
        }

        for (ToolExecutionRequest req : requests) {
            String fingerprint = req.name() + "|" + (req.arguments() != null ? req.arguments() : "");
            int count = callFingerprintCount.getOrDefault(fingerprint, 0) + 1;
            callFingerprintCount.put(fingerprint, count);

            if (count >= repeatStop) {
                log.warn("[LoopDetector] loop detected: tool '{}' called {} times with same args", req.name(), count);
                return DetectionResult.stop("重复调用同一工具同一参数 " + count + " 次，疑似死循环");
            } else if (count >= repeatWarn) {
                log.warn("[LoopDetector] loop warning: tool '{}' called {} times with same args", req.name(), count);
                return DetectionResult.warn("工具 " + req.name() + " 已重复调用 " + count + " 次，请考虑换参数或换工具");
            }
        }
        return DetectionResult.ok();
    }

    /** 记录工具执行结果，更新失败计数器，检测熔断。 */
    public DetectionResult recordToolResult(String toolName, boolean success) {
        if (success) {
            if (consecutiveFailures > 0) {
                log.debug("[LoopDetector] tool '{}' succeeded, resetting failure counter (was {})", toolName, consecutiveFailures);
            }
            consecutiveFailures = 0;
            toolFailureCount.remove(toolName);
            trippedTools.remove(toolName);
            return DetectionResult.ok();
        }

        consecutiveFailures++;
        int toolFails = toolFailureCount.getOrDefault(toolName, 0) + 1;
        toolFailureCount.put(toolName, toolFails);

        log.warn("[LoopDetector] tool '{}' failed: consecutiveFailures={}, toolFails={}",
                toolName, consecutiveFailures, toolFails);

        // 单工具熔断
        if (toolFails >= failureStopThreshold) {
            trippedTools.add(toolName);
            log.error("[LoopDetector] circuit breaker TRIPPED for tool '{}': {} consecutive failures", toolName, toolFails);
        }

        // 全局熔断
        if (consecutiveFailures >= failureStopThreshold) {
            log.error("[LoopDetector] global circuit breaker TRIPPED: {} consecutive failures across tools", consecutiveFailures);
            return DetectionResult.stop("工具连续失败 " + consecutiveFailures + " 次（熔断阈值 " +
                    failureStopThreshold + "），强制终止。请检查：1) 网络是否可用；2) 是否在编造参数绕过失败工具；" +
                    "3) 是否应该标记 blocked 并调整计划");
        }

        if (consecutiveFailures >= failureWarnThreshold) {
            return DetectionResult.warn("工具已连续失败 " + consecutiveFailures + " 次。" +
                    "请立即：1) 调用 todo_write 将当前任务标记为 blocked；2) 调整计划或换一种方式；" +
                    "3) 不要编造参数继续尝试同一类工具。再失败 " +
                    (failureStopThreshold - consecutiveFailures) + " 次将强制终止");
        }

        return DetectionResult.ok();
    }

    /** 记录 Todo 快照，检测无进展。 */
    public DetectionResult recordTodoSnapshot(String snapshot) {
        todoSnapshots.addLast(snapshot);
        if (todoSnapshots.size() > noProgressSteps) {
            todoSnapshots.removeFirst();
        }

        if (todoSnapshots.size() >= noProgressSteps) {
            Set<String> uniqueSnapshots = new HashSet<>(todoSnapshots);
            if (uniqueSnapshots.size() == 1) {
                stepsWithoutProgress++;
                if (stepsWithoutProgress >= 1) {
                    log.warn("[LoopDetector] no progress: todo unchanged for {} steps", noProgressSteps);
                    return DetectionResult.warn("连续 " + noProgressSteps + " 步 Todo 无变化，请检查是否陷入循环");
                }
            } else {
                stepsWithoutProgress = 0;
            }
        }
        return DetectionResult.ok();
    }

    public void reset() {
        callFingerprintCount.clear();
        todoSnapshots.clear();
        stepsWithoutProgress = 0;
        consecutiveFailures = 0;
        toolFailureCount.clear();
        trippedTools.clear();
    }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public boolean isToolTripped(String toolName) { return trippedTools.contains(toolName); }

    public record DetectionResult(Level level, String message) {
        public static DetectionResult ok() { return new DetectionResult(Level.OK, null); }
        public static DetectionResult warn(String msg) { return new DetectionResult(Level.WARN, msg); }
        public static DetectionResult stop(String msg) { return new DetectionResult(Level.STOP, msg); }

        public boolean shouldStop() { return level == Level.STOP; }
        public boolean shouldWarn() { return level == Level.WARN; }
    }

    public enum Level { OK, WARN, STOP }
}
