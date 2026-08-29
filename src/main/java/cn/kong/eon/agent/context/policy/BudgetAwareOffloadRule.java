package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.ArgumentOffloader;
import cn.kong.eon.agent.context.ToolSupport;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算感知的无损参数卸载（阶段 4）。
 * <p>
 * 与入站的 {@code ArgumentOffloadRule} 是<b>同一件事的两个入口</b>：
 * <ul>
 *   <li>入站规则：每次回填立刻做，覆盖正常路径（收益的主体，稳态上下文 −38%）</li>
 *   <li>本规则：预算投影吃紧时补做，覆盖入站时拿不到信息的场景——
 *       典型是从 transcript 恢复的历史会话，此时没有工具成功与否的上下文，
 *       入站规则无法判定"参数是否真的落盘"，只能留给本规则在看到结果块后补判</li>
 * </ul>
 * <p>
 * <b>为什么可以在水位不高时执行</b>：它只处理磁盘上已有完整副本的参数块，
 * 替换成"路径引用"是零信息损失操作。无损处置越早做，
 * 中间每一轮省下的重复发送成本越多——因为预算是上下文大小对时间的积分。
 * <p>
 * <b>硬约束</b>：替换文本必须由 {@link ArgumentOffloader} 生成——
 * 它会作为历史工具调用的 {@code arguments} 回传给模型，
 * 供应商校验格式，不是严格合法的 JSON 会被直接拒收（400）。
 */
public class BudgetAwareOffloadRule implements ContextRule {
    private static final Logger log = LoggerFactory.getLogger(BudgetAwareOffloadRule.class);

    private final double minRemainingTurns;
    private final int minChars;
    private final ToolSupport toolSupport;
    private final ObjectMapper objectMapper;

    public BudgetAwareOffloadRule(double minRemainingTurns, int minChars,
                                  ToolSupport toolSupport, ObjectMapper objectMapper) {
        this.minRemainingTurns = minRemainingTurns;
        this.minChars = Math.max(0, minChars);
        this.toolSupport = toolSupport != null ? toolSupport : ToolSupport.NONE;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "BudgetAwareOffload";
    }

    @Override
    public int level() {
        return LEVEL_LOSSLESS;
    }

    @Override
    public List<Trigger> triggers() {
        return List.of(new Trigger.BudgetProjection(minRemainingTurns));
    }

    @Override
    public RuleOutcome apply(RuleContext ctx) {
        Map<String, Boolean> resultSuccess = collectResultSuccess(ctx);

        long before = ctx.window().totalChars();
        int count = 0;

        for (ContextBlock block : ctx.window().blocks()) {
            if (!eligible(block, ctx, resultSuccess)) continue;
            if (offload(block)) count++;
        }

        long after = ctx.window().totalChars();
        if (count > 0) {
            log.info("[预算感知] 参数卸载: {} 个块 ({} -> {} 字符，无损)", count, before, after);
        }
        return RuleOutcome.of(count, before, after, "Offload×" + count);
    }

    private boolean eligible(ContextBlock block, RuleContext ctx, Map<String, Boolean> resultSuccess) {
        if (block.kind() != BlockKind.TOOL_ARGS) return false;
        if (block.isOffloaded() || block.isPruned()) return false;
        if (block.chars() <= minChars) return false;
        if (block.toolName() == null) return false;
        // 卸载的安全前提：内容在磁盘上另有完整副本
        if (!toolSupport.persistsArguments(block.toolName())) return false;
        // 尾部保护：当前轮的参数可能马上要被引用，留给入站规则处理
        if (block.turn() >= ctx.currentTurn()) return false;
        // 只有执行成功的调用才保证真的落盘了
        return Boolean.TRUE.equals(resultSuccess.get(block.toolCallId()));
    }

    /**
     * @return 是否真的卸载了；无法保持合法 JSON 时返回 false 并保持原样
     */
    private boolean offload(ContextBlock block) {
        int originalChars = block.chars();
        String path = ArgumentOffloader.extractPath(block.text(), objectMapper);
        String replacement = ArgumentOffloader.offload(block.text(), path, objectMapper);
        if (replacement == null) {
            log.debug("[预算感知] 参数卸载跳过: {}({}) 无可替换的超长字段",
                    block.toolName(), block.toolCallId());
            return false;
        }

        block.setText(replacement);
        block.markOffloaded();
        log.info("[预算感知] 参数卸载: {}({}) {} -> {} 字符 | 落盘于 {}",
                block.toolName(), block.toolCallId(), originalChars, replacement.length(),
                path != null ? path : "(路径未知)");
        return true;
    }

    /**
     * 收集每个 toolCallId 对应的结果块是否成功。
     * 这就是入站阶段拿不到、只能在策略阶段补判的信息。
     */
    private Map<String, Boolean> collectResultSuccess(RuleContext ctx) {
        Map<String, Boolean> map = new HashMap<>();
        for (ContextBlock block : ctx.window().blocks()) {
            if (block.kind() != BlockKind.TOOL_RESULT) continue;
            if (block.toolCallId() == null || block.success() == null) continue;
            // 已存在的判定优先保留 true（同 id 多个结果块时取乐观值，避免误判失败而错失卸载）
            if (Boolean.TRUE.equals(block.success())) {
                map.put(block.toolCallId(), Boolean.TRUE);
            } else {
                map.putIfAbsent(block.toolCallId(), Boolean.FALSE);
            }
        }
        return map;
    }

}
