package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.block.CompressionLevel;

/**
 * 压缩机制的运行参数。装配期从配置读入，运行期只读。
 * 不变量在构造时校验，保证本对象一旦建成即处于自洽状态。
 *
 * @param snipWaterLevel       SNIP 档水位下限
 * @param pruneWaterLevel      PRUNE 档水位下限
 * @param summarizeWaterLevel  SUMMARIZE 档水位下限
 * @param turnInterval         轮数触发周期，轮次序号为其整数倍时命中轮数入口
 * @param turnLevel            轮数入口命中且水位三档均未命中时执行的档位
 * @param tailGuardTurns       尾部保护区轮数，最近这些轮的内容不参与任何档位
 * @param snipKeepChars        头尾截断保留的字符数
 * @param offloadMinChars      参数块骨架化的最小字符数，低于此值骨架化反而更长
 * @param summarizeMaxInputChars  喂给摘要模型的最大字符数
 * @param summarizeMaxOutputChars 摘要输出的最大字符数
 */
public record CompressionSettings(
        double snipWaterLevel,
        double pruneWaterLevel,
        double summarizeWaterLevel,
        int turnInterval,
        CompressionLevel turnLevel,
        int tailGuardTurns,
        int snipKeepChars,
        int offloadMinChars,
        int summarizeMaxInputChars,
        int summarizeMaxOutputChars
) {
    public CompressionSettings {
        if (!(snipWaterLevel < pruneWaterLevel && pruneWaterLevel < summarizeWaterLevel)) {
            throw new IllegalArgumentException(
                    "压缩水位必须递增: snip < prune < summarize，实际为 "
                            + snipWaterLevel + " / " + pruneWaterLevel + " / " + summarizeWaterLevel);
        }
        if (turnInterval <= 0) {
            throw new IllegalArgumentException("turnInterval 必须为正整数，实际为 " + turnInterval);
        }
        if (turnLevel != CompressionLevel.SNIP && turnLevel != CompressionLevel.PRUNE) {
            throw new IllegalArgumentException(
                    "turnLevel 只能是 SNIP 或 PRUNE，实际为 " + turnLevel);
        }
        if (tailGuardTurns < 0) {
            throw new IllegalArgumentException("tailGuardTurns 不能为负，实际为 " + tailGuardTurns);
        }
    }
}
