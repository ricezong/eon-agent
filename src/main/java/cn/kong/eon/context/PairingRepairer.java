package cn.kong.eon.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配对修复器。保证 tool_use/tool_result 消息配对完整。
 * 四条规则：
 *   1. 匹配的 tool_result 移到 tool_use 之后
 *   2. 丢弃孤立的 tool_result（无对应 tool_use）
 *   3. 为缺失结果的 tool_use 插入合成错误消息
 *   4. 按 tool_use_id 去重
 */
public class PairingRepairer {
    private static final Logger log = LoggerFactory.getLogger(PairingRepairer.class);

    public List<ChatMessage> repair(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<ChatMessage> result = new ArrayList<>();
        Set<String> seenToolUseIds = new HashSet<>();
        Set<String> seenToolResultIds = new HashSet<>();
        int droppedResults = 0;
        int insertedSynthetics = 0;

        // 第一遍：收集所有已存在的 tool_result ID
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage trm) {
                seenToolResultIds.add(trm.id());
            }
        }

        // 第二遍：重建消息序列
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage ai) {
                if (ai.hasToolExecutionRequests()) {
                    // 去重
                    List<ToolExecutionRequest> filtered = new ArrayList<>();
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        if (!seenToolUseIds.contains(req.id())) {
                            seenToolUseIds.add(req.id());
                            filtered.add(req);
                        }
                    }
                    if (filtered.isEmpty()) {
                        log.debug("[Compress] PairingRepair: dropping duplicate AiMessage with all-seen tool_use IDs");
                        continue;
                    }
                    AiMessage filteredAi = ai.text() != null
                            ? AiMessage.from(ai.text(), filtered)
                            : AiMessage.from(filtered);
                    result.add(filteredAi);

                    // 为缺失结果的 tool_use 插入合成错误
                    for (ToolExecutionRequest req : filtered) {
                        if (!seenToolResultIds.contains(req.id())) {
                            log.warn("[Compress] PairingRepair: inserting synthetic error for orphan tool_use: {} ({})",
                                    req.id(), req.name());
                            insertedSynthetics++;
                            result.add(ToolExecutionResultMessage.from(req.id(), req.name(),
                                    "[SYNTHETIC] 工具结果缺失（可能被压缩），请重新调用此工具获取最新结果"));
                        }
                    }
                } else {
                    result.add(ai);
                }
            } else if (msg instanceof ToolExecutionResultMessage trm) {
                // 丢弃孤立的 tool_result
                if (!seenToolUseIds.contains(trm.id())) {
                    log.debug("[Compress] PairingRepair: dropping orphan result {}", trm.id());
                    droppedResults++;
                    continue;
                }
                result.add(trm);
            } else {
                result.add(msg);
            }
        }

        if (droppedResults > 0 || insertedSynthetics > 0) {
            log.info("[Compress] PairingRepair: dropped {} orphan results, inserted {} synthetic errors",
                    droppedResults, insertedSynthetics);
        }
        return result;
    }
}
