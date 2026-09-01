package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.Retention;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文窗口。块序列的一等持有者。
 * 职责：持有有序块列表、按轮次计算尾部保护区、维护 tool_use/tool_result 配对不变式。
 */
public class ContextWindow {
    private static final Logger log = LoggerFactory.getLogger(ContextWindow.class);

    private final List<ContextBlock> blocks = new ArrayList<>();

    /** 追加块。 */
    public void addAll(List<ContextBlock> newBlocks) {
        blocks.addAll(newBlocks);
    }

    /** 可变的块列表视图，供规则就地改写。 */
    public List<ContextBlock> blocks() {
        return blocks;
    }

    /** 只读视图 */
    public List<ContextBlock> view() {
        return Collections.unmodifiableList(blocks);
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public void clear() {
        blocks.clear();
    }

    /** 组装为 LangChain4j 消息序列。 */
    public List<ChatMessage> toMessages() {
        return BlockProjector.assemble(blocks);
    }

    /** 当前最大入站轮次。 */
    public int latestTurn() {
        int max = 0;
        for (ContextBlock block : blocks) {
            if (block.turn() > max) max = block.turn();
        }
        return max;
    }

    /**
     * 尾部保护区起始轮次：turn >= cutoff 的块不参与任何压缩。
     *
     * @param tailGuardTurns 保护的最近轮数
     */
    public int cutoffTurn(int tailGuardTurns) {
        return latestTurn() - tailGuardTurns;
    }

    /**
     * 删除 cutoffTurn 之前的所有可压缩块，返回被删除的块。
     * {@link Retention#VERBATIM} 块自动保留。
     */
    public List<ContextBlock> removeBefore(int cutoffTurn) {
        List<ContextBlock> removed = new ArrayList<>();
        List<ContextBlock> kept = new ArrayList<>(blocks.size());
        for (ContextBlock block : blocks) {
            if (block.turn() < cutoffTurn && block.retention().compressible()) {
                removed.add(block);
            } else {
                kept.add(block);
            }
        }
        blocks.clear();
        blocks.addAll(kept);
        return removed;
    }

    /**
     * 修复 tool_use / tool_result 配对：丢弃孤立的结果块，为缺失结果的调用块补合成结果。
     * 删除块会切断配对，LLM API 对此零容忍，所以这是窗口的结构不变式。
     */
    public void repairPairing() {
        java.util.Set<String> callIds = new java.util.HashSet<>();
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null) {
                callIds.add(block.toolCallId());
            }
        }

        List<ContextBlock> repaired = new ArrayList<>(blocks.size() + 4);
        java.util.Set<String> seenCallIds = new java.util.HashSet<>();
        java.util.Set<String> seenResultIds = new java.util.HashSet<>();
        int dropped = 0;
        int inserted = 0;

        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_RESULT) {
                String callId = block.toolCallId();
                if (callId == null || !callIds.contains(callId) || seenResultIds.contains(callId)) {
                    dropped++;
                    continue;
                }
                seenResultIds.add(callId);
                repaired.add(block);
            } else if (block.kind() == BlockKind.TOOL_ARGS) {
                String callId = block.toolCallId();
                if (callId != null && seenCallIds.contains(callId)) {
                    dropped++;
                    continue;
                }
                if (callId != null) seenCallIds.add(callId);
                repaired.add(block);
            } else {
                repaired.add(block);
            }
        }

        // 为缺失结果的调用块补合成结果，紧跟在其所属组内
        List<ContextBlock> withSynthetics = new ArrayList<>(repaired.size() + 4);
        for (ContextBlock block : repaired) {
            withSynthetics.add(block);
            if (block.kind() == BlockKind.TOOL_ARGS
                    && block.toolCallId() != null
                    && !seenResultIds.contains(block.toolCallId())) {
                withSynthetics.add(ContextBlock.builder()
                        .id(block.id() + "#synthetic")
                        .kind(BlockKind.TOOL_RESULT)
                        .retention(Retention.COMPRESSIBLE)
                        .groupId(block.groupId() + "#syn-" + block.toolCallId())
                        .ordinal(block.ordinal())
                        .turn(block.turn())
                        .toolName(block.toolName())
                        .toolCallId(block.toolCallId())
                        .text("[合成] 工具结果缺失（可能被压缩或卸载），请重新调用此工具获取最新结果")
                        .build());
                inserted++;
            }
        }

        blocks.clear();
        blocks.addAll(withSynthetics);

        if (dropped > 0 || inserted > 0) {
            log.info("[上下文] 配对修复: 丢弃 {} 个孤立块，插入 {} 个合成结果", dropped, inserted);
        }
    }

    /** 窗口内块的总字符数。 */
    public long totalChars() {
        long total = 0;
        for (ContextBlock block : blocks) total += block.chars();
        return total;
    }

    /** 相对入站已节省的字符总数（无损卸载 + 有损压缩）。 */
    public long savedChars() {
        long total = 0;
        for (ContextBlock block : blocks) total += block.savedChars();
        return total;
    }
}
