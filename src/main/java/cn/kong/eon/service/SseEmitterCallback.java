package cn.kong.eon.service;

import cn.kong.eon.agent.TurnCallback;
import cn.kong.eon.api.dto.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * SSE 回调实现。将 {@link TurnCallback} 事件转为 {@link AgentEvent} 并通过 {@link SseEmitter} 推送。
 */
public class SseEmitterCallback implements TurnCallback {
    private static final Logger log = LoggerFactory.getLogger(SseEmitterCallback.class);

    private final String sessionId;
    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseEmitterCallback(String sessionId, SseEmitter emitter, ObjectMapper objectMapper) {
        this.sessionId = sessionId;
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onRunStart(String sessionId, String userInput) {
        sendEvent(AgentEvent.runStart(sessionId));
    }

    @Override
    public void onTurnStart(int turnNumber) {
        sendEvent(AgentEvent.turnStart(turnNumber));
    }

    @Override
    public void onLlmResponse(String thought, List<String> toolNames) {
        sendEvent(AgentEvent.llmResponse(0, thought, toolNames));
    }

    @Override
    public void onToolStart(String toolName, String toolCallId) {
        sendEvent(AgentEvent.toolStart(0, toolName, toolCallId));
    }

    @Override
    public void onToolResult(String toolName, boolean success, String summary) {
        sendEvent(AgentEvent.toolResult(0, toolName, success, summary));
    }

    @Override
    public void onTurnEnd(int turnCount, int totalTokens) {
        sendEvent(AgentEvent.turnEnd(turnCount, totalTokens));
    }

    @Override
    public void onOutput(String output, int turnCount, int totalTokens) {
        sendEvent(AgentEvent.done(sessionId, output, turnCount, totalTokens));
    }

    @Override
    public void onTerminate(String reason, int turnCount, int totalTokens) {
        sendEvent(AgentEvent.terminated(sessionId, reason, turnCount, totalTokens));
    }

    @Override
    public void onError(String error) {
        sendEvent(AgentEvent.error(sessionId, error));
    }

    private void sendEvent(AgentEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(event.getType().name())
                    .data(json));
        } catch (Exception e) {
            log.warn("SSE send failed: type={}, error={}", event.getType(), e.getMessage());
        }
    }
}
