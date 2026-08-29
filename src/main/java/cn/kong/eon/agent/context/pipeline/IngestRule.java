package cn.kong.eon.agent.context.pipeline;

import cn.kong.eon.agent.context.block.ContextBlock;

/**
 * 入站规则。在消息进入上下文的那一刻对内容块做处置，<b>不依赖水位</b>。
 * <p>
 * 与在站策略规则（{@code ContextRule}）的分工：
 * <table>
 *   <tr><th></th><th>入站规则</th><th>在站策略规则</th></tr>
 *   <tr><td>时机</td><td>消息回填的那一刻</td><td>每次模型调用前</td></tr>
 *   <tr><td>判据</td><td>内容自身（大小、是否已落盘）</td><td>水位 / 轮数 / 预算投影</td></tr>
 *   <tr><td>性质</td><td>以无损为主</td><td>以有损为主</td></tr>
 * </table>
 * <p>
 * 把"控制进入上下文的内容大小"这件事收敛到入站管线，
 * 才有了一个统一关卡——过去工具结果的落盘策略在工具执行层、
 * 而工具参数没有任何策略，导致同一个函数里相邻两行 append，
 * 一个有处理、一个裸奔。
 */
public interface IngestRule {

    String name();

    /** 执行顺序，小的先执行 */
    int order();

    /**
     * 本规则是否作用于该块。
     */
    boolean appliesTo(ContextBlock block, IngestContext ctx);

    /**
     * 就地改写块内容。
     */
    void apply(ContextBlock block, IngestContext ctx);
}
