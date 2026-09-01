package cn.kong.eon.agent.context.block;

import cn.kong.eon.agent.context.ToolSupport;
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
 */
public final class BlockProjector {

    private BlockProjector() {
    }

    /**
     * 把一条消息爆炸为若干内容块。
     *
     * @param groupId 消息组 id，同一条消息的所有块共享
     * @param turn    入站轮次
     * @param lookup  工具元数据查询，决定 TOOL_ARGS 块的保留策略
     */
    public static List<ContextBlock> explode(ChatMessage msg, String groupId, int turn, ToolSupport lookup) {
        List<ContextBlock> blocks = new ArrayList<>();
        ToolSupport meta = lookup != null ? lookup : ToolSupport.NONE;

        if (msg instanceof UserMessage um) {
            blocks.add(base(BlockKind.USER_INPUT, Retention.VERBATIM, groupId, 0, turn)
                    .text(um.singleText() != null ? um.singleText() : "")
                    .build());
            return blocks;
        }

        if (msg instanceof AiMessage am) {
            int ordinal = 0;
            if (am.text() != null && !am.text().isBlank()) {
                blocks.add(base(BlockKind.AI_TEXT, Retention.COMPRESSIBLE, groupId, ordinal++, turn)
                        .text(am.text())
                        .build());
            }
            if (am.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                    Retention retention = meta.persistsArguments(req.name())
                            ? Retention.OFFLOADABLE
                            : Retention.COMPRESSIBLE;
                    blocks.add(base(BlockKind.TOOL_ARGS, retention, groupId, ordinal++, turn)
                            .toolName(req.name())
                            .toolCallId(req.id())
                            .text(req.arguments() != null ? req.arguments() : "")
                            .build());
                }
            }
            if (blocks.isEmpty()) {
                blocks.add(base(BlockKind.AI_TEXT, Retention.COMPRESSIBLE, groupId, 0, turn)
                        .text(am.text() != null ? am.text() : "")
                        .build());
            }
            return blocks;
        }

        if (msg instanceof ToolExecutionResultMessage trm) {
            blocks.add(base(BlockKind.TOOL_RESULT, Retention.COMPRESSIBLE, groupId, 0, turn)
                    .toolName(trm.toolName())
                    .toolCallId(trm.id())
                    .text(trm.text() != null ? trm.text() : "")
                    .build());
            return blocks;
        }

        blocks.add(base(BlockKind.OTHER, Retention.COMPRESSIBLE, groupId, 0, turn)
                .text(String.valueOf(msg))
                .build());
        return blocks;
    }

    /**
     * 把整个消息列表爆炸为块序列（每条消息一个 group）。
     *
     * @param turn   所有块的入站轮次
     * @param lookup 工具元数据查询
     */
    public static List<ContextBlock> explodeAll(List<ChatMessage> messages, int turn, ToolSupport lookup) {
        List<ContextBlock> all = new ArrayList<>();
        if (messages == null) return all;
        for (int i = 0; i < messages.size(); i++) {
            all.addAll(explode(messages.get(i), "m" + i, turn, lookup));
        }
        return all;
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
                    // 卸载后的 arguments 必须仍是严格合法的 JSON，否则供应商会拒收整个请求
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

    private static ContextBlock.Builder base(BlockKind kind,
                                             Retention retention,
                                             String groupId,
                                             int ordinal,
                                             int turn) {
        return ContextBlock.builder()
                .id(groupId + "#" + ordinal)
                .kind(kind)
                .retention(retention)
                .groupId(groupId)
                .ordinal(ordinal)
                .turn(turn);
    }
}
