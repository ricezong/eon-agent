package cn.kong.eon.web.sse;

import cn.kong.eon.event.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将 AgentEvent 转为前端渲染所需的 Map。实时与回放两条链路共用。 */
@Component
public class EventFormatter implements AgentEventVisitor<Map<String, Object>> {

    /** 所有事件统一携带的会话 ID 字段名。 */
    public static final String SESSION_ID_FIELD = "session_id";

    /** sessionId 走参数而非成员字段，使本类无状态、可被多个会话共享。 */
    public Map<String, Object> format(AgentEvent event, String sessionId) {
        Map<String, Object> data = event.accept(this);
        data.put(SESSION_ID_FIELD, sessionId);
        return data;
    }

    @Override
    public Map<String, Object> visitSessionStart(SessionStart e) {
        Map<String, Object> data = base(e);
        if (e.title() != null) {
            data.put("title", e.title());
        }
        return data;
    }

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
        return data;
    }

    @Override
    public Map<String, Object> visitToolResult(AgentToolResult e) {
        Map<String, Object> data = base(e);
        data.put("turn_id", e.turnId());
        data.put("tool_use_id", e.toolUseId());
        data.put("name", e.name());
        data.put("content", e.content());
        data.put("structured_content", e.toolResultView());
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
    public Map<String, Object> visitUserMessage(UserMessage e) {
        Map<String, Object> data = base(e);
        data.put("content", e.content());
        return data;
    }

    @Override
    public Map<String, Object> visitUnknown(AgentEvent e) {
        return base(e);
    }

    private Map<String, Object> base(AgentEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", e.type());
        data.put("timestamp", e.timestamp().toString());
        return data;
    }
}
