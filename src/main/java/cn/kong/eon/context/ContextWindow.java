package cn.kong.eon.context;

import cn.kong.eon.context.block.BlockKind;
import cn.kong.eon.context.block.MessageBlockCodec;
import cn.kong.eon.context.block.ContextBlock;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文窗口。持有有序块列表，负责尾部保护区与 tool_use/tool_result 配对不变式。
 * 保护区按位置下标划定：窗口末尾 N 个块不参与任何档位的处置。
 */
public class ContextWindow {

    /** 合成结果块的 id 后缀 */
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
        return MessageBlockCodec.assemble(blocks);
    }

    /**
     * 保护区起始下标。
     */
    public int protectedFrom(int tailGuardBlocks) {
        return Math.max(0, blocks.size() - tailGuardBlocks);
    }

    /**
     * 删除保护区之前的全部块。调用方必须先完成摘要写入。
     *
     * @return 删除后、配对修复前的首块消息序号；窗口清空返回 -1。
     *         该值可能被 {@link #repairPairing()} 改变，水位线应以修复后的 {@link #firstSurvivorSeq()} 为准。
     */
    public int removeBefore(int protectedFrom) {
        int removeCount = Math.min(protectedFrom, blocks.size());
        if (removeCount > 0) {
            List<ContextBlock> kept = new ArrayList<>(blocks.subList(removeCount, blocks.size()));
            blocks.clear();
            blocks.addAll(kept);
        }
        return blocks.isEmpty() ? -1 : blocks.get(0).messageSeq();
    }

    /**
     * 窗口首块的消息序号；窗口为空返回 -1。水位线须在 {@link #repairPairing()} 之后取此值。
     */
    public int firstSurvivorSeq() {
        return blocks.isEmpty() ? -1 : blocks.get(0).messageSeq();
    }

    /**
     * 修复 tool_use/tool_result 配对：丢弃孤立结果块，为缺失结果的调用块补合成结果。
     */
    public void repairPairing() {
        // 预扫描：收集所有调用块 ID 和结果块 ID
        Set<String> callIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null) {
                callIds.add(block.toolCallId());
            } else if (block.kind() == BlockKind.TOOL_RESULT && block.toolCallId() != null) {
                resultIds.add(block.toolCallId());
            }
        }

        // 逐块过滤：丢弃孤立结果（对应调用块已不在窗口），为缺失结果的调用补合成结果
        List<ContextBlock> repaired = new ArrayList<>(blocks.size());

        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_RESULT) {
                String callId = block.toolCallId();
                // 丢弃孤立结果：callId 为空或对应的调用块已不在窗口
                if (!callIds.contains(callId)) {
                    continue;
                }
            }
            repaired.add(block);
            // 为缺结果的调用块补合成结果（仅在窗口中确实没有该调用的真实结果时才补）
            if (block.kind() == BlockKind.TOOL_ARGS && !resultIds.contains(block.toolCallId())) {
                repaired.add(ContextBlock.builder()
                        .id(block.id() + SYNTHETIC_ID_SUFFIX)
                        .kind(BlockKind.TOOL_RESULT)
                        .groupId(block.groupId() + SYNTHETIC_GROUP_SUFFIX + block.toolCallId())
                        .ordinal(block.ordinal())
                        .messageSeq(block.messageSeq())
                        .toolName(block.toolName())
                        .toolCallId(block.toolCallId())
                        .text("[合成] 工具结果缺失，请重新调用此工具获取最新结果")
                        .build());
            }
        }

        blocks.clear();
        blocks.addAll(repaired);
    }
}
