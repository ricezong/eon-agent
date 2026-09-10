package cn.kong.eon.web;

import cn.kong.eon.app.AgentBootstrap;
import cn.kong.eon.app.AgentChatRequest;
import cn.kong.eon.app.RunResult;
import cn.kong.eon.event.TurnListener;
import cn.kong.eon.web.sse.SseTurnListener;
import cn.kong.eon.web.sse.TranscriptReplayer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent HTTP/SSE 控制器。提供对话、会话管理、中断接口。
 */
@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentBootstrap app;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentController(AgentBootstrap app,
                           @Qualifier("sseExecutor") ExecutorService sseExecutor,
                           ObjectMapper objectMapper) {
        this.app = app;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对话
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 发送消息并流式接收 Agent 响应（唯一对话入口）。
     * <p>
     * sessionId 为空时自动创建新会话，非空时恢复已有会话。
     * SSE 事件流：agent.delta → agent.thinking → agent.message → agent.tool_use → agent.tool_result → session.usage → session.status
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.execute(() -> {
            try {
                List<TurnListener> listeners = new ArrayList<>();
                listeners.add(new SseTurnListener(emitter, objectMapper));

                RunResult result = app.run(request, listeners);
                emitter.send(SseEmitter.event().name("agent.message.final")
                        .data(Map.of("content", result.content(), "session_id", result.sessionId())));
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 对话失败", e);
                try {
                    emitter.send(SseEmitter.event().name("session.error")
                            .data(Map.of("message", e.getMessage(), "type", "runtime_error")));
                } catch (Exception ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 中断指定会话的当前任务。 */
    @PostMapping("/interrupt")
    public Map<String, Object> interrupt(@RequestBody InterruptRequest request) {
        boolean interrupted = app.interrupt(request.sessionId());
        return Map.of("status", interrupted ? "interrupted" : "no_session");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话管理
    // ═══════════════════════════════════════════════════════════════════

    /** 列出历史会话。 */
    @GetMapping("/sessions")
    public List<SessionListItem> listSessions(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        var sessions = app.getSessionRegistry().list(userId);
        List<SessionListItem> result = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            var s = sessions.get(i);
            result.add(new SessionListItem(
                    i + 1,
                    s.sessionId(),
                    s.title(),
                    s.messageCount(),
                    s.lastActivityAt().toString()
            ));
        }
        return result;
    }

    /** 删除会话（硬删除：SQLite 记录 + 会话目录）。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "default") String userId) {
        boolean deleted = app.getSessionRegistry().delete(userId, sessionId);
        return Map.of("status", deleted ? "deleted" : "not_found", "session_id", sessionId);
    }

    /**
     * 恢复会话
     */
    @GetMapping("/sessions/{sessionId}")
    public List<Map<String, Object>> getSession(@PathVariable String sessionId) {
        var transcriptPath = app.getTranscriptPath(sessionId);
        TranscriptReplayer replayer = new TranscriptReplayer(objectMapper);
        return replayer.replay(transcriptPath);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  请求/响应 DTO
    // ═══════════════════════════════════════════════════════════════════

    public record InterruptRequest(String sessionId) {}

    public record SessionListItem(
            int index,
            String sessionId,
            String title,
            long messageCount,
            String lastActivityAt
    ) {}
}
