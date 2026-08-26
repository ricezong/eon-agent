package cn.kong.eon.agent.loop;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 死循环检测器。三种检测：
 * ① 重复调用——同一工具同一参数连续调用超过阈值。
 * ② 无进展——连续 N 步 Todo 状态未变化。
 * ③ 单工具熔断——单个工具连续失败超过阈值，熔断该工具（不影响其他工具）。
 */
public class LoopDetector {
    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    private final int repeatWarn;
    private final int repeatStop;
    private final int noProgressSteps;
    private final int failureWarnThreshold;       // 单工具失败告警阈值
    private final int failureStopThreshold;       // 单工具失败熔断阈值

    private final Map<String, Integer> callFingerprintCount = new HashMap<>();  // 调用指纹连续计数
    private final Deque<String> todoSnapshots = new ArrayDeque<>();             // Todo 快照队列
    private int stepsWithoutProgress = 0;

    private final Map<String, Integer> toolFailureCount = new HashMap<>();      // 单工具连续失败计数
    private final Set<String> trippedTools = new HashSet<>();                   // 已熔断工具集

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

    /**
     * 记录工具调用，检测重复调用和已熔断工具。
     * 熔断工具返回 WARN（提示 LLM 换方案），不返回 STOP（不阻止其他工具执行）。
     */
    public DetectionResult recordToolCalls(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return DetectionResult.ok();
        }

        // 收集所有已熔断的工具名
        List<String> trippedNames = new ArrayList<>();
        for (ToolExecutionRequest req : requests) {
            if (trippedTools.contains(req.name()) && !trippedNames.contains(req.name())) {
                trippedNames.add(req.name());
            }
        }
        if (!trippedNames.isEmpty()) {
            String names = String.join(", ", trippedNames);
            log.warn("[LoopDetector] 熔断工具: [{}] 已被封锁", names);
            return DetectionResult.warn("工具 " + names + " 已被熔断（连续失败过多），" +
                    "请标记 blocked 或调整计划，不要再调用这些工具");
        }

        for (ToolExecutionRequest req : requests) {
            String fingerprint = req.name() + "|" + (req.arguments() != null ? req.arguments() : "");
            int count = callFingerprintCount.getOrDefault(fingerprint, 0) + 1;
            callFingerprintCount.put(fingerprint, count);

            if (count >= repeatStop) {
                log.warn("[LoopDetector] 死循环检测: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                return DetectionResult.stop("重复调用同一工具同一参数 " + count + " 次，疑似死循环");
            } else if (count >= repeatWarn) {
                log.warn("[LoopDetector] 循环告警: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                return DetectionResult.warn("工具 " + req.name() + " 已重复调用 " + count + " 次，请考虑换参数或换工具");
            }
        }
        return DetectionResult.ok();
    }

    /**
     * 记录工具执行结果，更新单工具失败计数器，检测熔断。
     * 成功时重置该工具的失败计数和指纹计数。
     */
    public DetectionResult recordToolResult(String toolName, boolean success) {
        if (success) {
            toolFailureCount.remove(toolName);
            trippedTools.remove(toolName);
            resetFingerprintsForTool(toolName);
            return DetectionResult.ok();
        }

        // 已熔断的工具不再累积计数，避免日志冗余
        if (trippedTools.contains(toolName)) {
            return DetectionResult.ok();
        }

        int toolFails = toolFailureCount.getOrDefault(toolName, 0) + 1;
        toolFailureCount.put(toolName, toolFails);

        log.warn("[LoopDetector] 工具 '{}' 失败: 连续失败次数={}", toolName, toolFails);

        // 单工具熔断
        if (toolFails >= failureStopThreshold) {
            trippedTools.add(toolName);
            log.error("[LoopDetector] 工具 '{}' 已熔断: 连续失败 {} 次", toolName, toolFails);
            return DetectionResult.stop("工具 " + toolName + " 连续失败 " + toolFails + " 次，已熔断。" +
                    "请标记 blocked 或调整计划，不要再调用此工具，其他工具仍可正常使用");
        }

        if (toolFails >= failureWarnThreshold) {
            return DetectionResult.warn("工具 " + toolName + " 已连续失败 " + toolFails + " 次。" +
                    "请立即：1) 调用 todo_write 将当前任务标记为 blocked；2) 调整计划或换一种方式；" +
                    "3) 不要编造参数继续尝试同一工具。再失败 " +
                    (failureStopThreshold - toolFails) + " 次将熔断此工具");
        }

        return DetectionResult.ok();
    }

    /**
     * 记录 Todo 快照，检测无进展。
     */
    public DetectionResult recordTodoSnapshot(String snapshot) {
        todoSnapshots.addLast(snapshot);
        if (todoSnapshots.size() > noProgressSteps) {
            todoSnapshots.removeFirst();
        }

        if (todoSnapshots.size() >= noProgressSteps) {
            Set<String> uniqueSnapshots = new HashSet<>(todoSnapshots);
            if (uniqueSnapshots.size() == 1) {
                stepsWithoutProgress++;
                if (stepsWithoutProgress >= 2) {
                    log.warn("[LoopDetector] 无进展: Todo 连续 {} 个窗口（{} 步）未变化", stepsWithoutProgress, noProgressSteps);
                    return DetectionResult.warn("连续 " + (noProgressSteps * stepsWithoutProgress) + " 步 Todo 无变化，请检查是否陷入循环");
                }
            } else {
                stepsWithoutProgress = 0;
            }
        }
        return DetectionResult.ok();
    }

    public boolean isToolTripped(String toolName) {
        return trippedTools.contains(toolName);
    }

    /**
     * 重置指定工具的指纹计数（成功调用后允许相同参数再次使用）。
     */
    private void resetFingerprintsForTool(String toolName) {
        callFingerprintCount.entrySet().removeIf(e -> e.getKey().startsWith(toolName + "|"));
    }

    public record DetectionResult(Level level, String message) {
        public static DetectionResult ok() {
            return new DetectionResult(Level.OK, null);
        }

        public static DetectionResult warn(String msg) {
            return new DetectionResult(Level.WARN, msg);
        }

        public static DetectionResult stop(String msg) {
            return new DetectionResult(Level.STOP, msg);
        }

        public boolean shouldStop() {
            return level == Level.STOP;
        }

        public boolean shouldWarn() {
            return level == Level.WARN;
        }
    }

    public enum Level {OK, WARN, STOP}
}
