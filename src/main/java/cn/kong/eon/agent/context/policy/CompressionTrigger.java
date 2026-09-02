package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.block.CompressionLevel;

/**
 * 档位判定。每轮算出一个档位，水位入口优先于轮数入口。
 * <p>
 * 水位三档自上而下比较，命中即返回，轮数分支不会被求值——
 * "水位优先"由此成为结构上的保证，而不是靠调用顺序的约定。
 * 轮数入口是周期性对账点：轮次序号为周期整数倍时，执行一个固定的轻量档位，
 * 使上下文在没有冲高水位的情况下也能逐步收敛。
 */
public final class CompressionTrigger {

    private CompressionTrigger() {
    }

    /**
     * 判定本轮档位。
     *
     * @param metrics   当前度量，取其中的水位
     * @param turnCount 当前轮次序号
     * @param settings  运行参数
     * @return 本轮档位；无需处置时返回 {@link CompressionLevel#NONE}
     */
    public static CompressionLevel resolve(ContextMetrics metrics,
                                           int turnCount,
                                           CompressionSettings settings) {
        if (metrics == null) return CompressionLevel.NONE;

        double water = metrics.waterLevel();
        if (water >= settings.summarizeWaterLevel()) return CompressionLevel.SUMMARIZE;
        if (water >= settings.pruneWaterLevel()) return CompressionLevel.PRUNE;
        if (water >= settings.snipWaterLevel()) return CompressionLevel.SNIP;

        if (turnCount > 0 && turnCount % settings.turnInterval() == 0) {
            return settings.turnLevel();
        }
        return CompressionLevel.NONE;
    }
}
