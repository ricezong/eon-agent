package cn.kong.eon.web.sse;

import cn.kong.eon.event.AgentEvent;
import cn.kong.eon.event.AgentEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 事件监听器。将 AgentEvent 序列化为 SSE 帧推送到前端。
 * <p>
 * 事件格式化委托给 {@link AgentEventFormatter}，与恢复渲染共用同一套格式化代码。
 */
public class SseAgentEventListener implements AgentEventListener {
    private static final Logger log = LoggerFactory.getLogger(SseAgentEventListener.class);

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final AgentEventFormatter formatter;

    /**
     * @param formatter 应用级单例格式化器。此处只持有引用，不新建——
     *                  本监听器的 {@code onEvent()} 每收到一个事件就被调用一次。
     */
    public SseAgentEventListener(SseEmitter emitter, ObjectMapper objectMapper,
                                 AgentEventFormatter formatter) {
        this.emitter = emitter;
        this.mapper = objectMapper;
        this.formatter = formatter;
    }

    @Override
    public void onEvent(AgentEvent event) {
        try {
            Map<String, Object> data = event.accept(formatter);

            String json = mapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event.type()).data(json));

        } catch (IOException e) {
            log.warn("SSE 发送失败: {}", e.getMessage());
        } catch (Exception e) {
            log.error("SSE 事件序列化失败: {}", e.getMessage(), e);
        }
    }
}
