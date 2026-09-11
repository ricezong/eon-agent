package cn.kong.eon.engine.guard;

import java.util.HashMap;
import java.util.Map;

/**
 * 循环检测器：统计同一任务内「同工具 + 同参数」的重复调用次数。
 * <p>
 * 原为 {@code LoopDetectHook} 的私有状态，外置后挂在 {@code TaskScope} 上，
 * 使 Hook 可以变无状态单例，避免多会话并发时互相污染计数。
 */
public final class LoopDetector {

    private final int warnThreshold;
    private final int stopThreshold;

    /** 指纹 → 调用次数，同一任务内累计 */
    private final Map<String, Integer> callFingerprintCount = new HashMap<>();

    public LoopDetector(int warnThreshold, int stopThreshold) {
        this.warnThreshold = warnThreshold;
        this.stopThreshold = stopThreshold;
    }

    public int warnThreshold() {
        return warnThreshold;
    }

    public int stopThreshold() {
        return stopThreshold;
    }

    /** 记录一次调用指纹，返回该指纹的累计次数（首次为 1）。 */
    public int record(String fingerprint) {
        int count = callFingerprintCount.getOrDefault(fingerprint, 0) + 1;
        callFingerprintCount.put(fingerprint, count);
        return count;
    }
}
