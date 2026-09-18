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
 * 映射规则：user→UserMessage；ai→AgentThinking?+AgentMessage?+AgentToolUse×N；
 * tool→AgentToolResult；system→跳过。结尾补发 session.status(idle/replay_completed)。
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

            events.add(SessionStatus.idle("replay_completed"));
            log.info("账本回放完成: {} 行 → {} 个事件", lines.size(), events.size());
        } catch (IOException e) {
            log.error("读取账本失败: {}", ledgerPath, e);
        }

        return events;
    }

    /** 将单条 SerializedMessage 转为对应的 AgentEvent 列表（system 与未知类型不产生事件）。 */
    private List<AgentEvent> toEvents(LedgerStore.SerializedMessage sm, String turnId, int seq) {
        Instant ts = Instant.now();
        return switch (sm.type) {
            case "ai" -> aiToEvents(sm, turnId, seq, ts);
            case "user" -> userToEvents(sm, ts);
            case "tool" -> toolToEvents(sm, turnId, ts);
            default -> List.of();
        };
    }

    /** ai 消息：thinking、文本、工具调用各自独立还原，文本不因伴随工具调用而丢弃。 */
    private List<AgentEvent> aiToEvents(LedgerStore.SerializedMessage sm, String turnId,
                                        int seq, Instant ts) {
        List<AgentEvent> events = new ArrayList<>();

        if (sm.thinking != null && !sm.thinking.isBlank()) {
            events.add(new AgentThinking(turnId, sm.thinking, ts));
        }
        if (sm.content != null && !sm.content.isBlank()) {
            events.add(new AgentMessage(turnId, "msg_replay_" + seq,
                    List.of(ContentPart.text(sm.content)), ts));
        }
        if (sm.toolCalls != null) {
            for (LedgerStore.ToolCallRef ref : sm.toolCalls) {
                events.add(new AgentToolUse(turnId, ref.id, ref.name, ref.arguments, ts));
            }
        }
        return events;
    }

    /** user 消息：还原为用户消息事件，前端据此恢复用户气泡并切分回复轮次。 */
    private List<AgentEvent> userToEvents(LedgerStore.SerializedMessage sm, Instant ts) {
        if (sm.content == null || sm.content.isBlank()) {
            return List.of();
        }
        return List.of(new UserMessage(sm.content, ts));
    }

    /** tool 消息：还原为工具结果事件，缺失的结构化视图回退为纯文本视图。 */
    private List<AgentEvent> toolToEvents(LedgerStore.SerializedMessage sm, String turnId,
                                          Instant ts) {
        String content = sm.content != null ? sm.content : "";
        ToolResultView view = sm.toolResultView != null
                ? sm.toolResultView : ToolResultView.text(content);
        return List.of(new AgentToolResult(turnId, sm.toolCallId,
                sm.toolName != null ? sm.toolName : "unknown",
                content, view, Boolean.TRUE.equals(sm.success), ts));
    }
}
