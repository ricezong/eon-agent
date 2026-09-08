package cn.kong.eon.agent.exec;

import cn.kong.eon.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具熔断器。跟踪单个工具的连续失败次数，超过阈值后熔断，
 * 冷却 N 轮后自动恢复。熔断期间执行层拦截跳过，不终止会话。
 */
public class ToolBreaker {
    private static final Logger log = LoggerFactory.getLogger(ToolBreaker.class);

    private static final String TRIPPED_MSG = "工具 %1$s 连续失败 %2$d 次，已熔断 %3$d 轮。期间不要再调用此工具，请用其他方式完成";
    private static final String WARN_MSG = """
            工具 %1$s 已连续失败 %2$d 次。
            请立即：
            1) 调用 todo_write 将当前任务标记为 blocked；
            2) 调整计划或换一种方式；
            3) 不要编造参数继续尝试同一工具。再失败 %3$d 次将熔断此工具
            """;
    private static final String BLOCK_MSG = "工具 %s 已熔断，请使用其他工具或调整方案";
    private static final String RECOVERED_MSG = "工具 %s 熔断冷却已结束，恢复可用";

    private final int warnThreshold;
    private final int stopThreshold;
    private final int cooldownTurns;
    private final Map<String, Integer> failureCount = new HashMap<>();
    private final Map<String, Integer> trippedCooldown = new HashMap<>();

    public ToolBreaker(AgentConfig.LoopDetectConfig cfg) {
        this(cfg.getFailureWarn(), cfg.getFailureStop(), cfg.getCooldownTurns());
    }

    public ToolBreaker(int warnThreshold, int stopThreshold, int cooldownTurns) {
        this.warnThreshold = warnThreshold;
        this.stopThreshold = stopThreshold;
        this.cooldownTurns = cooldownTurns;
    }

    /**
     * 记录工具执行结果，更新失败计数。
     * 成功时清除计数；失败时累加，达到阈值时熔断并返回 warn（写 nudge）。
     *
     * @return 非空字符串表示需要注入 nudge 的提示文案；空串表示无需提示
     */
    public String record(String toolName, boolean success) {
        if (success) {
            failureCount.remove(toolName);
            return "";
        }

        if (isTripped(toolName)) {
            return "";
        }

        int fails = failureCount.getOrDefault(toolName, 0) + 1;
        failureCount.put(toolName, fails);
        log.warn("[ToolBreaker] 工具 '{}' 失败: 连续 {} 次", toolName, fails);

        if (fails >= stopThreshold) {
            trippedCooldown.put(toolName, cooldownTurns);
            log.error("[ToolBreaker] 工具 '{}' 已熔断: 连续失败 {} 次, 冷却 {} 轮", toolName, fails, cooldownTurns);
            return String.format(TRIPPED_MSG, toolName, fails, cooldownTurns);
        }

        if (fails >= warnThreshold) {
            return String.format(WARN_MSG, toolName, fails, stopThreshold - fails);
        }

        return "";
    }

    /** 工具是否已被熔断（冷却期内）。 */
    public boolean isTripped(String toolName) {
        return trippedCooldown.containsKey(toolName);
    }

    /** 熔断拦截提示文案。 */
    public String trippedMessage(String toolName) {
        return String.format(BLOCK_MSG, toolName);
    }

    /**
     * 每轮结束时调用，推进冷却计数。冷却归零时自动解除熔断。
     *
     * @return 恢复的工具对应的提示文案列表，用于写入 nudge
     */
    public List<String> tickCooldown() {
        if (trippedCooldown.isEmpty()) return List.of();

        List<String> recovered = new ArrayList<>();
        Iterator<Map.Entry<String, Integer>> it = trippedCooldown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                String toolName = entry.getKey();
                it.remove();
                failureCount.remove(toolName);
                recovered.add(String.format(RECOVERED_MSG, toolName));
                log.info("[ToolBreaker] 工具 '{}' 冷却结束，恢复可用", toolName);
            } else {
                entry.setValue(remaining);
            }
        }
        return recovered;
    }

    /** 清空全部熔断状态，在每个任务开始时调用。 */
    public void reset() {
        failureCount.clear();
        trippedCooldown.clear();
        log.debug("[ToolBreaker] 熔断状态已重置");
    }
}
