package cn.kong.eon.api.sse;

import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.AgentEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/** 将 AgentEvent 序列化为 SSE 帧推送前端。实例绑定单个会话，故每帧都能携带 session_id。 */
public class SseEventListener implements AgentEventListener {
    private static final Logger log = LoggerFactory.getLogger(SseEventListener.class);

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final EventFormatter formatter;
    private final String sessionId;

    public SseEventListener(SseEmitter emitter, ObjectMapper objectMapper,
                            EventFormatter formatter, String sessionId) {
        this.emitter = emitter;
        this.mapper = objectMapper;
        this.formatter = formatter;
        this.sessionId = sessionId;
    }

    @Override
    public void onEvent(AgentEvent event) {
        try {
            Map<String, Object> data = formatter.format(event, sessionId);

            String json = mapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event.type()).data(json));

        } catch (IOException e) {
            log.warn("SSE 发送失败: {}", e.getMessage());
        } catch (Exception e) {
            log.error("SSE 事件序列化失败: {}", e.getMessage(), e);
        }
    }
}
