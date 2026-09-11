package cn.kong.eon.runtime;

import cn.kong.eon.event.*;
import cn.kong.eon.store.ledger.TranscriptLedger;
import cn.kong.eon.store.ledger.TranscriptLedger.SerializedMessage;
import cn.kong.eon.tool.model.ToolResultView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账本回放器。读取 transcript.jsonl，将 SerializedMessage 还原为 AgentEvent 列表。
 * <p>
 * 只负责"账本 → 事件"这一步；事件到前端渲染数据的格式化由接入层（web.sse）完成，
 * 保证应用层不反向依赖传输层。
 * <p>
 * 账本中每行消息的映射规则：
 * <ul>
 *   <li>user → 不产出事件（用户消息在前端由 chat 请求本身渲染）</li>
 *   <li>ai（无 toolCalls）→ AgentThinking（如有 thinking）+ AgentMessage</li>
 *   <li>ai（有 toolCalls）→ AgentThinking（如有 thinking）+ AgentToolUse × N</li>
 *   <li>tool → AgentToolResult</li>
 *   <li>system → 跳过（系统消息不需要前端渲染）</li>
 * </ul>
 * AgentDelta（流式增量）是瞬态事件，不参与回放。
 * SessionUsage 和 SessionStatus 是运行时状态事件，回放时补发一个最终的 idle 状态。
 */
public class TranscriptReplayer {
    private static final Logger log = LoggerFactory.getLogger(TranscriptReplayer.class);

    private final ObjectMapper mapper;

    public TranscriptReplayer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 读取账本，还原为事件列表。
     * 由调用方用 {@code AgentEventFormatter} 格式化，即可与实时 SSE 输出结构完全一致。
     */
    public List<AgentEvent> replay(Path transcriptPath) {
        List<AgentEvent> events = new ArrayList<>();
        if (!Files.exists(transcriptPath)) {
            log.warn("账本不存在: {}", transcriptPath);
            return events;
        }

        try {
            List<String> lines = Files.readAllLines(transcriptPath);
            String turnId = "replay_" + UUID.randomUUID().toString().substring(0, 8);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) continue;

                SerializedMessage sm;
                try {
                    sm = mapper.readValue(line, SerializedMessage.class);
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
            log.error("读取账本失败: {}", transcriptPath, e);
        }

        return events;
    }

    /**
     * 将单条 SerializedMessage 转为对应的 AgentEvent 列表。
     */
    private List<AgentEvent> toEvents(SerializedMessage sm, String turnId, int seq) {
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
                for (TranscriptLedger.ToolCallRef ref : sm.toolCalls) {
                    events.add(new AgentToolUse(turnId, ref.id, ref.name,
                            ref.arguments, ts));
                }
            } else if (sm.content != null && !sm.content.isBlank()) {
                // 无工具调用 → agent.message 事件
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
