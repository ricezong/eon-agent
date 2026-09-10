package cn.kong.eon.web;

import cn.kong.eon.agent.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件监听器。将 TurnEvent 序列化为 SSE 帧推送到前端。
 */
public class SseTurnListener implements TurnListener {
    private static final Logger log = LoggerFactory.getLogger(SseTurnListener.class);

    private final SseEmitter emitter;
    private final ObjectMapper mapper;

    public SseTurnListener(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.mapper = objectMapper;
    }

    @Override
    public void onEvent(TurnEvent event) {
        try {
            Map<String, Object> data = event.accept(new SseEventFormatter());

            String json = mapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event.type()).data(json));

        } catch (IOException e) {
            log.warn("SSE 发送失败: {}", e.getMessage());
        } catch (Exception e) {
            log.error("SSE 事件序列化失败: {}", e.getMessage(), e);
        }
    }

    /** SSE 事件格式化 Visitor。 */
    private static class SseEventFormatter implements TurnEventVisitor<Map<String, Object>> {

        @Override
        public Map<String, Object> visitDelta(AgentDelta e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("kind", e.kind());
            if (e.delta() != null) data.put("delta", e.delta());
            return data;
        }

        @Override
        public Map<String, Object> visitThinking(AgentThinking e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("content", e.content());
            return data;
        }

        @Override
        public Map<String, Object> visitMessage(AgentMessage e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("message_id", e.messageId());
            data.put("content", e.content());
            return data;
        }

        @Override
        public Map<String, Object> visitToolUse(AgentToolUse e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("tool_use_id", e.toolUseId());
            data.put("name", e.name());
            data.put("input", e.input());
            data.put("evaluated_permission", e.evaluatedPermission());
            return data;
        }

        @Override
        public Map<String, Object> visitToolResult(AgentToolResult e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("tool_use_id", e.toolUseId());
            data.put("name", e.name());
            data.put("content", e.content());
            data.put("structured_content", e.structuredContent());
            data.put("success", e.success());
            return data;
        }

        @Override
        public Map<String, Object> visitUsage(SessionUsage e) {
            Map<String, Object> data = base(e);
            data.put("turn_id", e.turnId());
            data.put("message_id", e.messageId());
            data.put("prompt_tokens", e.promptTokens());
            data.put("completion_tokens", e.completionTokens());
            data.put("total_tokens", e.totalTokens());
            return data;
        }

        @Override
        public Map<String, Object> visitStatus(SessionStatus e) {
            Map<String, Object> data = base(e);
            data.put("status", e.status());
            if (e.stopReason() != null) data.put("stop_reason", e.stopReason());
            return data;
        }

        @Override
        public Map<String, Object> visitError(SessionError e) {
            Map<String, Object> data = base(e);
            data.put("message", e.message());
            data.put("error_type", e.type());
            return data;
        }

        @Override
        public Map<String, Object> visitUnknown(TurnEvent e) {
            return base(e);
        }

        private Map<String, Object> base(TurnEvent e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", e.type());
            data.put("timestamp", e.timestamp().toString());
            return data;
        }
    }
}
