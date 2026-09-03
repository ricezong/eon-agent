package cn.kong.eon.agent.loop;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 循环检测器。三种检测各管一类信号，互不重叠：
 * ① 重复调用——同一轮内同一工具同一参数被重复调用。跨轮的重复尝试不归它管，见 ③。
 * ② 无进展——连续 N 步 Todo 状态未变化。
 * ③ 单工具熔断——单个工具连续失败超过阈值，熔断该工具（不影响其他工具）。
 */
public class LoopDetector {
    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    // ── DetectionResult 消息模板（进 nudge 或 StopReason，模型可见） ──

    /** 死循环停止原因：%d=同一参数重复调用次数 */
    private static final String REPEAT_STOP = "重复调用同一工具同一参数 %d 次，疑似死循环";

    /** 循环告警提示：%1$s=工具名，%2$d=重复调用次数 */
    private static final String REPEAT_WARN = "工具 %1$s 已重复调用 %2$d 次，请考虑换参数或换工具";

    /** 单工具熔断提示：%1$s=工具名，%2$d=连续失败次数 */
    private static final String FAILURE_STOP = """
            工具 %1$s 连续失败 %2$d 次，已熔断。
            请标记 blocked 或调整计划，不要再调用此工具，其他工具仍可正常使用
            """;

    /** 熔断预警提示：%1$s=工具名，%2$d=已连续失败次数，%3$d=距熔断还差次数 */
    private static final String FAILURE_WARN = """
            工具 %1$s 已连续失败 %2$d 次。
            请立即：
            1) 调用 todo_write 将当前任务标记为 blocked；
            2) 调整计划或换一种方式；
            3) 不要编造参数继续尝试同一工具。再失败 %3$d 次将熔断此工具
            """;

    /** 无进展提示：%d=连续无变化的步数 */
    private static final String NO_PROGRESS_WARN = "连续 %d 步 Todo 无变化，请检查是否陷入循环";

    private final int repeatWarn;
    private final int repeatStop;
    private final int noProgressSteps;
    private final int failureWarnThreshold;
    private final int failureStopThreshold;

    private final Map<String, Integer> callFingerprintCount = new HashMap<>();
    private final Deque<String> todoSnapshots = new ArrayDeque<>();
    private int stepsWithoutProgress = 0;

    private final Map<String, Integer> toolFailureCount = new HashMap<>();
    private final Set<String> trippedTools = new HashSet<>();

    public LoopDetector(int repeatWarn, int repeatStop, int noProgressSteps,
                        int failureWarnThreshold, int failureStopThreshold) {
        this.repeatWarn = repeatWarn;
        this.repeatStop = repeatStop;
        this.noProgressSteps = noProgressSteps;
        this.failureWarnThreshold = failureWarnThreshold;
        this.failureStopThreshold = failureStopThreshold;
    }

    /**
     * 记录本轮模型发出的工具调用，检测同一批次内的重复调用。
     *
     * <p>已熔断的工具不在此提示：它由执行阶段直接拦截并把熔断说明回填为工具结果，
     * 本轮即可达模型，比经 nudge 下一轮生效更及时，此处再提示只是重复。
     */
    public DetectionResult recordToolCalls(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return DetectionResult.ok();
        }

        for (ToolExecutionRequest req : requests) {
            String fingerprint = req.name() + "|" + (req.arguments() != null ? req.arguments() : "");
            int count = callFingerprintCount.getOrDefault(fingerprint, 0) + 1;
            callFingerprintCount.put(fingerprint, count);

            if (count >= repeatStop) {
                log.warn("[LoopDetector] 死循环检测: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                return DetectionResult.stop(String.format(REPEAT_STOP, count));
            } else if (count >= repeatWarn) {
                log.warn("[LoopDetector] 循环告警: 工具 '{}' 以相同参数调用 {} 次", req.name(), count);
                return DetectionResult.warn(String.format(REPEAT_WARN, req.name(), count));
            }
        }
        return DetectionResult.ok();
    }

    /**
     * 记录工具执行结果，更新单工具失败计数器，检测熔断。
     *
     * <p>无论成败都关闭该工具的指纹窗口：重复调用检测只覆盖单轮同批次，跨轮的重复尝试
     * 归失败计数管。否则同一批失败重试会被两套机制同时观测，而本类的重复检测在
     * PostModel 阶段先判定，会抢在熔断之前判死循环，使熔断永远无法触发。
     */
    public DetectionResult recordToolResult(String toolName, boolean success) {
        resetFingerprintsForTool(toolName);

        if (success) {
            toolFailureCount.remove(toolName);
            trippedTools.remove(toolName);
            return DetectionResult.ok();
        }

        // 已熔断的工具不再累积计数
        if (trippedTools.contains(toolName)) {
            return DetectionResult.ok();
        }

        int toolFails = toolFailureCount.getOrDefault(toolName, 0) + 1;
        toolFailureCount.put(toolName, toolFails);

        log.warn("[LoopDetector] 工具 '{}' 失败: 连续失败次数={}", toolName, toolFails);

        if (toolFails >= failureStopThreshold) {
            trippedTools.add(toolName);
            log.error("[LoopDetector] 工具 '{}' 已熔断: 连续失败 {} 次", toolName, toolFails);
            return DetectionResult.stop(String.format(FAILURE_STOP, toolName, toolFails));
        }

        if (toolFails >= failureWarnThreshold) {
            return DetectionResult.warn(String.format(
                    FAILURE_WARN, toolName, toolFails, failureStopThreshold - toolFails));
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
                    return DetectionResult.warn(String.format(
                            NO_PROGRESS_WARN, noProgressSteps * stepsWithoutProgress));
                }
            } else {
                stepsWithoutProgress = 0;
            }
        }
        return DetectionResult.ok();
    }

    /** 工具是否已被熔断。 */
    public boolean isToolTripped(String toolName) {
        return trippedTools.contains(toolName);
    }

    /**
     * 清空全部检测状态，在每个任务开始时调用。
     *
     * <p>本类是会话级单例，但内部的熔断集合、指纹计数、失败计数、Todo 快照全是任务级状态。
     * 不重置会把上一任务的判定带进新任务：被熔断的工具在新任务里仍被 {@link #isToolTripped}
     * 拦截而永久不可用，指纹计数也会跨任务累加导致首次调用即判死循环。
     */
    public void reset() {
        callFingerprintCount.clear();
        todoSnapshots.clear();
        stepsWithoutProgress = 0;
        toolFailureCount.clear();
        trippedTools.clear();
        log.debug("[LoopDetector] 检测状态已重置");
    }

    /** 关闭指定工具的指纹窗口：清掉它在本轮累积的全部指纹计数。 */
    private void resetFingerprintsForTool(String toolName) {
        callFingerprintCount.entrySet().removeIf(e -> e.getKey().startsWith(toolName + "|"));
    }

    /** 检测结果。 */
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

    /** 检测级别。 */
    public enum Level {OK, WARN, STOP}
}
