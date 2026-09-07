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
 * 上下文窗口。块序列的一等持有者。
 * 职责：持有有序块列表、按位置划定尾部保护区、维护 tool_use/tool_result 配对不变式。
 * <p>
 * <b>保护区按位置下标划定</b>：窗口末尾固定 N 个块不参与任何档位的处置。
 * 不锚定在用户消息位置——当前任务正在产出的内容（往往几十上百个块）恰恰最占空间，
 * 锚在最后一条用户输入上会让它们全部免压，可压缩区间几乎不增长。
 */
public class ContextWindow {

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

    /**
     * 保护区起始下标：窗口末尾 tailGuardBlocks 个块不参与任何档位的处置。
     *
     * @param tailGuardBlocks 从末尾向前保护的块数
     */
    public int protectedFrom(int tailGuardBlocks) {
        return Math.max(0, blocks.size() - tailGuardBlocks);
    }

    /**
     * 删除保护区之前的全部块（下标 &lt; protectedFrom）。
     * <p>
     * 保留策略不做类型区分——用户消息同样删除，其诉求由摘要承载。
     * 调用方必须先完成摘要写入，否则删除即永久丢失。
     *
     * @return 第一个幸存块的消息序号（恢复时的回放起点）；窗口被清空时返回 -1
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
     * 修复 tool_use / tool_result 配对：丢弃孤立的结果块，为缺失结果的调用块补合成结果。
     * 删除块会切断配对，LLM API 对此零容忍，所以这是窗口的结构不变式。
     * <p>
     * 压缩删除（{@link #removeBefore}）之后必须调用，重建被删块破坏的配对。
     * <p>
     * 三遍扫描：
     * <ol>
     *   <li>预扫描：收集所有调用块的工具调用 ID，作为结果块合法性的判定基准</li>
     *   <li>过滤：剔除无效块并登记幸存者，产出干净的块序列</li>
     *   <li>补齐：给没有结果的调用块插入合成结果，紧跟其所属调用块</li>
     * </ol>
     */
    public void repairPairing() {
        // 第一遍：预扫描全部调用块的工具调用 ID。
        // 结果块是否合法取决于"是否存在对应的调用块"，与两者在序列中的先后无关，
        // 所以必须先收集完整集合，不能在过滤时边走边判。
        Set<String> callIds = new HashSet<>();
        for (ContextBlock block : blocks) {
            if (block.kind() == BlockKind.TOOL_ARGS && block.toolCallId() != null) {
                callIds.add(block.toolCallId());
            }
        }

        // 第二遍：逐块过滤，产出配对完好的块序列。
        // 两个登记簿记录各自幸存过的调用 ID：判重靠它们，
        // 第三遍判断"哪些调用还缺结果"也靠 seenResultIds。
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

        // 第三遍：为没有幸存结果的调用块补合成结果，紧跟该调用块插入，
        // 保证组装消息时每个调用块都有配对的结果块。
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
