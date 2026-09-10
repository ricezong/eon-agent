package cn.kong.eon.context.policy;

import cn.kong.eon.context.block.CompressionLevel;

/**
 * 压缩参数快照。由组合根从 AgentConfig 构造后注入，
 * 使 context 层不必依赖 config 包（依赖倒置，参数随消费者走）。
 */
public record CompressionSettings(
        /** SNIP 档水位下限 */
        double snipWaterLevel,
        /** PRUNE 档水位下限 */
        double pruneWaterLevel,
        /** SUMMARIZE 档水位下限 */
        double summarizeWaterLevel,
        /** 轮数触发周期：轮次序号为其整数倍时命中轮数入口 */
        int turnInterval,
        /** 轮数入口命中且水位三档均未命中时执行的档位 */
        CompressionLevel turnLevel,
        /** 尾部保护区块数：窗口末尾这些块不参与任何档位 */
        int tailGuardBlocks,
        /** 参数块字段裁剪的最小字符数，短参数裁剪后反而更长 */
        int argsPruneMinChars,
        /** 头尾保留截断时保留的字符数 */
        int snipKeepChars) {
}
