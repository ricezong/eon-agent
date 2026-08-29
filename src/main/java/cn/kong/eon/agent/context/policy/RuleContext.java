package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ContextMetrics;
import cn.kong.eon.agent.context.ContextWindow;
import cn.kong.eon.model.CompressionState;

/**
 * 规则执行上下文。策略机在调用规则时打包传入，规则按需取用。
 */
public record RuleContext(
        ContextWindow window,
        ContextMetrics metrics,
        CompressionState compressionState,
        /** 尾部保护区截止轮次：turn < 此值的可压缩块才参与处置 */
        int cutoffTurn,
        int currentTurn
) {
}
