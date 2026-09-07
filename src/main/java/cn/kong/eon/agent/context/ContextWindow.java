package cn.kong.eon.agent.context;

import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.BlockProjector;
import cn.kong.eon.agent.context.block.ContextBlock;
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
        return BlockProjector.assemble(blocks);
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
     * @return 第一个幸存块的消息序号；窗口被清空时返回 -1
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
     * 修复 tool_use/tool_result 配对：丢弃孤立结果块，为缺失结果的调用块补合成结果。
     * 压缩删除后必须调用。
     */
    public void repairPairing() {
        // 第一遍：收集全部调用块的工具调用 ID
        Set<String> callIds = new HashSet<>();
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null) {
                callIds.add(block.toolCallId());
            }
        }

        // 第二遍：逐块过滤，产出配对完好的块序列
        List<ContextBlock> repaired = new ArrayList<>(blocks.size() + 4);
        Set<String> seenCallIds = new HashSet<>();
        Set<String> seenResultIds = new HashSet<>();

        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_RESULT) {
                String callId = block.toolCallId();
                // 丢弃条件（满足任一）：
                //   callId 为 null        —— 没有 ID，无从配对
                //   callIds 不含 callId   —— 对应的调用块已不在窗口（孤立结果）
                //   seenResultIds 已含    —— 这个调用的结果已收过一份，当前是重复
                if (callId == null || !callIds.contains(callId) || seenResultIds.contains(callId)) {
                    continue;
                }
                seenResultIds.add(callId);
                repaired.add(block);
            } else if (block.kind() == BlockKind.TOOL_ARGS) {
                String callId = block.toolCallId();
                // 丢弃条件：seenCallIds 已含 —— 同一 ID 的调用块已收过一份，当前是重复
                if (callId != null && seenCallIds.contains(callId)) {
                    continue;
                }
                if (callId != null) seenCallIds.add(callId);
                repaired.add(block);
            } else {
                // 用户输入、AI 文本等，不参与配对，直接保留
                repaired.add(block);
            }
        }

        // 第三遍：为缺结果的调用块补合成结果
        List<ContextBlock> withSynthetics = new ArrayList<>(repaired.size() + 4);
        for (ContextBlock block : repaired) {
            withSynthetics.add(block);
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null && !seenResultIds.contains(block.toolCallId())) {
                withSynthetics.add(ContextBlock.builder()
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
        blocks.addAll(withSynthetics);
    }
}
