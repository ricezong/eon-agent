package cn.kong.eon.store.ledger;

import cn.kong.eon.event.*;
import cn.kong.eon.tool.model.ToolResultView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账本回放器。读取 ledger.jsonl，将 SerializedMessage 还原为 AgentEvent 列表。
 * 映射规则：user→不产出事件；ai(无toolCalls)→AgentThinking+AgentMessage；
 * ai(有toolCalls)→AgentThinking+AgentToolUse×N；tool→AgentToolResult；system→跳过。
 */
@Component
public class LedgerReplayer {
    private static final Logger log = LoggerFactory.getLogger(LedgerReplayer.class);

    private final ObjectMapper mapper;

    public LedgerReplayer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取账本，还原为事件列表。
     * 由调用方用 {@code AgentEventFormatter} 格式化，即可与实时 SSE 输出结构完全一致。
     */
    public List<AgentEvent> replay(Path ledgerPath) {
        List<AgentEvent> events = new ArrayList<>();
        if (!Files.exists(ledgerPath)) {
            log.warn("账本不存在: {}", ledgerPath);
            return events;
        }

        try {
            List<String> lines = Files.readAllLines(ledgerPath);
            String turnId = "replay_" + UUID.randomUUID().toString().substring(0, 8);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;

                LedgerStore.SerializedMessage sm;
                try {
                    sm = mapper.readValue(line, LedgerStore.SerializedMessage.class);
                } catch (Exception e) {
                    log.warn("回放时反序列化失败，跳过第 {} 行: {}", i, e.getMessage());
                    continue;
                }

                events.addAll(toEvents(sm, turnId, i));
            }

            // 补发一个最终的 session.status: idle
            events.add(SessionStatus.idle("replay_completed"));

            log.info("账本回放完成: {} 行 → {} 个事件", lines.size(), events.size());
        } catch (IOException e) {
            log.error("读取账本失败: {}", ledgerPath, e);
        }

        return events;
    }

    /**
     * 将单条 SerializedMessage 转为对应的 AgentEvent 列表。
     */
    private List<AgentEvent> toEvents(LedgerStore.SerializedMessage sm, String turnId, int seq) {
        List<AgentEvent> events = new ArrayList<>();
        Instant ts = Instant.now();

        if ("ai".equals(sm.type)) {
            String messageId = "msg_replay_" + seq;

            // thinking 事件
            if (sm.thinking != null && !sm.thinking.isBlank()) {
                events.add(new AgentThinking(turnId, sm.thinking, ts));
            }

            // 有工具调用 → tool_use 事件
            if (sm.toolCalls != null && !sm.toolCalls.isEmpty()) {
                for (LedgerStore.ToolCallRef ref : sm.toolCalls) {
                    events.add(new AgentToolUse(turnId, ref.id, ref.name,
                            ref.arguments, ts));
                }
            } else if (sm.content != null && !sm.content.isBlank()) {
                // 无工具调用 → engine.message 事件
                events.add(new AgentMessage(turnId, messageId,
                        List.of(ContentPart.text(sm.content)), ts));
            }
        } else if ("tool".equals(sm.type)) {
            // tool_result 事件
            boolean success = Boolean.TRUE.equals(sm.success);
            String content = sm.content != null ? sm.content : "";
            ToolResultView structured = sm.toolResultView != null
                    ? sm.toolResultView : ToolResultView.text(content);
            events.add(new AgentToolResult(turnId, sm.toolCallId,
                    sm.toolName != null ? sm.toolName : "unknown",
                    content, structured, success, ts));
        }
        // user 和 system 消息不产出前端渲染事件

        return events;
    }

}
