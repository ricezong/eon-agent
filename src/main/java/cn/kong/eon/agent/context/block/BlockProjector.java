package cn.kong.eon.agent.context.block;

import cn.kong.eon.agent.context.ContextTags;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投射层：ChatMessage ⇄ List&lt;ContextBlock&gt; 双向转换。
 * 爆炸（explode）：UserMessage → [USER_INPUT]，
 * AiMessage(text, reqs) → [AI_TEXT, TOOL_ARGS × N]，ToolExecutionResultMessage → [TOOL_RESULT]。
 * 组装（assemble）是逆操作：按 groupId 归并，组内按 ordinal 排序。
 * <p>
 * <b>这里只做一件事：划分可独立处置的内容单元</b>。不判定可压缩性（全类型一视同仁），
 * 不标记轮次（保护区分界由窗口的位置结构决定）。"磁盘上有没有副本"只由入站管线的
 * ArtifactSpillRule 在落盘成功时标记。
 */
public final class BlockProjector {

    private BlockProjector() {
    }

    /**
     * 把一条消息爆炸为若干内容块。
     *
     * @param messageSeq 消息在 JSONL 账本中的序号，由唯一知道账本长度的 JsonlStore 发放；
     *                   同一条消息的所有块共享它，groupId 也由它派生，保证回放后与账本对得上号
     */
    public static List<ContextBlock> explode(ChatMessage msg, int messageSeq) {
        String groupId = "g" + messageSeq;
        List<ContextBlock> blocks = new ArrayList<>();

        if (msg instanceof UserMessage um) {
            blocks.add(base(BlockKind.USER_INPUT, groupId, 0, messageSeq)
                    .text(um.singleText() != null ? um.singleText() : "")
                    .build());
            return blocks;
        }

        if (msg instanceof AiMessage am) {
            int ordinal = 0;
            if (am.text() != null && !am.text().isBlank()) {
                blocks.add(base(BlockKind.AI_TEXT, groupId, ordinal++, messageSeq)
                        .text(am.text())
                        .build());
            }
            if (am.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    blocks.add(base(BlockKind.TOOL_ARGS, groupId, ordinal++, messageSeq)
                            .toolName(req.name())
                            .toolCallId(req.id())
                            .text(req.arguments() != null ? req.arguments() : "")
                            .build());
                }
            }
            if (blocks.isEmpty()) {
                blocks.add(base(BlockKind.AI_TEXT, groupId, 0, messageSeq)
                        .text(am.text() != null ? am.text() : "")
                        .build());
            }
            return blocks;
        }

        if (msg instanceof ToolExecutionResultMessage trm) {
            blocks.add(base(BlockKind.TOOL_RESULT, groupId, 0, messageSeq)
                    .toolName(trm.toolName())
                    .toolCallId(trm.id())
                    .text(trm.text() != null ? trm.text() : "")
                    .build());
            return blocks;
        }

        blocks.add(base(BlockKind.OTHER, groupId, 0, messageSeq)
                .text(String.valueOf(msg))
                .build());
        return blocks;
    }

    /**
     * 把块序列组装回消息序列（逆操作）。
     * 按 groupId 首次出现顺序归并，组内按 ordinal 升序还原。
     */
    public static List<ChatMessage> assemble(List<ContextBlock> blocks) {
        Map<String, List<ContextBlock>> groups = new LinkedHashMap<>();
        for (ContextBlock block : blocks) {
            groups.computeIfAbsent(block.groupId(), k -> new ArrayList<>()).add(block);
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (List<ContextBlock> group : groups.values()) {
            group.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
            ChatMessage msg = assembleGroup(group);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }

    private static ChatMessage assembleGroup(List<ContextBlock> group) {
        ContextBlock first = group.get(0);
        switch (first.kind()) {
            case USER_INPUT -> {
                return UserMessage.from(joinText(group));
            }
            case TOOL_RESULT -> {
                return ToolExecutionResultMessage.from(
                        first.toolCallId(),
                        first.toolName() != null ? first.toolName() : "unknown",
                        joinText(group));
            }
            case AI_TEXT, TOOL_ARGS -> {
                String text = null;
                List<ToolExecutionRequest> requests = new ArrayList<>();
                for (ContextBlock block : group) {
                    if (block.kind() == BlockKind.AI_TEXT) {
                        String rendered = ContextTags.render(block);
                        text = (text == null) ? rendered : text + "\n" + rendered;
                    } else if (block.kind() == BlockKind.TOOL_ARGS) {
                        requests.add(ToolExecutionRequest.builder()
                                .id(block.toolCallId())
                                .name(block.toolName())
                                // 唯一不渲染的块：arguments 会原样回传模型并接受格式校验，
                                // 包上标签就不是合法 JSON，供应商会直接拒收整个请求
                                .arguments(block.text())
                                .build());
                    }
                }
                if (!requests.isEmpty()) {
                    // 处置后的 arguments 必须仍是严格合法的 JSON，否则供应商会拒收整个请求
                    return (text != null && !text.isBlank())
                            ? AiMessage.from(text, requests)
                            : AiMessage.from(requests);
                }
                return AiMessage.from(text != null ? text : "");
            }
            default -> {
                return null;
            }
        }
    }

    private static String joinText(List<ContextBlock> group) {
        StringBuilder sb = new StringBuilder();
        for (ContextBlock block : group) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(block.text());
        }
        return sb.toString();
    }

    /** 块 id 的组内分隔符，id = groupId + 分隔符 + ordinal */
    private static final String ID_SEPARATOR = "#";

    private static ContextBlock.Builder base(BlockKind kind, String groupId, int ordinal, int messageSeq) {
        return ContextBlock.builder()
                .id(groupId + ID_SEPARATOR + ordinal)
                .kind(kind)
                .groupId(groupId)
                .ordinal(ordinal)
                .messageSeq(messageSeq);
    }
}
