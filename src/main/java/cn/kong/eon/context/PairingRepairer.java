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
 * 配对修复器。
 * 对应技术方案第 3.5 节四条规则。
 * 任何截断、压缩、恢复操作之后，必须执行配对修复。
 */
public class PairingRepairer {
    private static final Logger log = LoggerFactory.getLogger(PairingRepairer.class);

    /**
     * 执行配对修复四条规则：
     * 1. 匹配的 tool_result 移到 tool_use 之后
     * 2. 丢弃孤立的 tool_result
     * 3. 为缺失结果的 tool_use 插入合成错误 tool_result
     * 4. 按 tool_use_id 去重
     */
    public List<ChatMessage> repair(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<ChatMessage> result = new ArrayList<>();
        Set<String> seenToolUseIds = new HashSet<>();
        Set<String> seenToolResultIds = new HashSet<>();

        // 第一遍：收集所有已存在的 tool_result ID
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage trm) {
                seenToolResultIds.add(trm.id());
            }
        }

        // 第二遍：重建消息序列
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage ai) {
                // 规则 4：按 tool_use_id 去重
                if (ai.hasToolExecutionRequests()) {
                    List<ToolExecutionRequest> filtered = new ArrayList<>();
                    for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                        if (!seenToolUseIds.contains(req.id())) {
                            seenToolUseIds.add(req.id());
                            filtered.add(req);
                        }
                    }
                    if (filtered.isEmpty()) {
                        // 所有 tool_use 都重复了，跳过这条消息
                        log.debug("Dropping duplicate AiMessage with all-seen tool_use IDs");
                        continue;
                    }
                    AiMessage filteredAi = ai.text() != null
                            ? AiMessage.from(ai.text(), filtered)
                            : AiMessage.from(filtered);
                    result.add(filteredAi);

                    // 规则 3：为缺失结果的 tool_use 插入合成错误
                    for (ToolExecutionRequest req : filtered) {
                        if (!seenToolResultIds.contains(req.id())) {
                            log.warn("Inserting synthetic error for orphan tool_use: {} ({})",
                                    req.id(), req.name());
                            result.add(ToolExecutionResultMessage.from(req.id(), req.name(),
                                    "[SYNTHETIC] 工具结果缺失（可能被压缩），请重新调用此工具获取最新结果"));
                        }
                    }
                } else {
                    result.add(ai);
                }
            } else if (msg instanceof ToolExecutionResultMessage trm) {
                // 规则 2：丢弃孤立的 tool_result（没有对应 tool_use 的）
                if (!seenToolUseIds.contains(trm.id())) {
                    log.debug("Dropping orphan ToolExecutionResultMessage: {}", trm.id());
                    continue;
                }
                result.add(trm);
            } else {
                // SystemMessage / UserMessage 直接保留
                result.add(msg);
            }
        }

        return result;
    }
}
