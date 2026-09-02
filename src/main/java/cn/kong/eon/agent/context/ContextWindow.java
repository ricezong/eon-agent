package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
import cn.kong.eon.agent.context.block.Retention;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文窗口。块序列的一等持有者。
 * 职责：持有有序块列表、按轮次计算尾部保护区、维护 tool_use/tool_result 配对不变式。
 */
public class ContextWindow {
    private static final Logger log = LoggerFactory.getLogger(ContextWindow.class);

    /** 合成结果块的 id / groupId 后缀，用于与真实块区分 */
    private static final String SYNTHETIC_ID_SUFFIX = "#synthetic";
    private static final String SYNTHETIC_GROUP_SUFFIX = "#syn-";

    private final List<ContextBlock> blocks = new ArrayList<>();

    /** 追加块。 */
    public void addAll(List<ContextBlock> newBlocks) {
        blocks.addAll(newBlocks);
    }

    /** 块列表，供处置逻辑就地改写。 */
    public List<ContextBlock> blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
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
     * 尾部保护区起始轮次：turn >= cutoff 的块不参与任何档位的处置。
     * 保护最近 tailGuardTurns 轮，即 latestTurn 与 latestTurn - tailGuardTurns + 1 之间的所有轮次。
     *
     * @param tailGuardTurns 保护的最近轮数
     */
    public int cutoffTurn(int tailGuardTurns) {
        return latestTurn() - tailGuardTurns + 1;
    }

    /**
     * 删除 cutoffTurn 之前的所有可改写块，返回被删除的块。
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
        Set<String> callIds = new HashSet<>();
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null) {
                callIds.add(block.toolCallId());
            }
        }

        List<ContextBlock> repaired = new ArrayList<>(blocks.size() + 4);
        Set<String> seenCallIds = new HashSet<>();
        Set<String> seenResultIds = new HashSet<>();
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
                        .id(block.id() + SYNTHETIC_ID_SUFFIX)
                        .kind(BlockKind.TOOL_RESULT)
                        .retention(Retention.COMPRESSIBLE)
                        .groupId(block.groupId() + SYNTHETIC_GROUP_SUFFIX + block.toolCallId())
                        .ordinal(block.ordinal())
                        .turn(block.turn())
                        .toolName(block.toolName())
                        .toolCallId(block.toolCallId())
                        .text("[合成] 工具结果缺失，请重新调用此工具获取最新结果")
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

    /** 相对入站已节省的字符总数。 */
    public long savedChars() {
        long total = 0;
        for (ContextBlock block : blocks) total += block.savedChars();
        return total;
    }
}
