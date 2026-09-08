package cn.kong.eon.agent.context.block;

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
 * 投射层：ChatMessage ⇄ ContextBlock 双向转换。
 */
public final class BlockProjector {

    /**
     * 把一条消息爆炸为若干内容块。
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
        return blocks;
    }

    /**
     * 把块序列组装回消息序列。按 groupId 归并，组内按 ordinal 排序。
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
                return UserMessage.from(wrap("user_input", joinText(group)));
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
                        text = (text == null) ? block.text() : text + "\n" + block.text();
                    } else if (block.kind() == BlockKind.TOOL_ARGS) {
                        requests.add(ToolExecutionRequest.builder()
                                .id(block.toolCallId())
                                .name(block.toolName())
                                .arguments(block.text())
                                .build());
                    }
                }
                if (!requests.isEmpty()) {
                    // 处置后的 arguments 必须是合法 JSON
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

    /** 用 XML 标签包裹内容。 */
    private static String wrap(String label, String content) {
        return "<" + label + ">\n" + content + "\n</" + label + ">";
    }

    /** 块 id 组内分隔符 */
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
