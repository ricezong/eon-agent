package cn.kong.eon.web;

import cn.kong.eon.app.AgentBootstrap;
import cn.kong.eon.agent.event.TurnListener;
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
 * Agent HTTP/SSE 控制器。提供对话、会话管理、中断等接口。
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
     * 发送消息并流式接收 Agent 响应。
     * SSE 事件流：agent.delta → agent.thinking → agent.message → agent.tool_use → agent.tool_result → session.usage → session.status
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.execute(() -> {
            try {
                List<TurnListener> listeners = new ArrayList<>();
                listeners.add(new SseTurnListener(emitter, objectMapper));

                String output = app.run(request.message(), listeners);
                emitter.send(SseEmitter.event().name("agent.message.final").data(output));
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

    /**
     * 中断当前任务。
     */
    @PostMapping("/interrupt")
    public Map<String, Object> interrupt() {
        if (app.isSessionInitialized() && app.getSession() != null) {
            app.getSession().getSessionState().requestInterrupt();
            return Map.of("status", "interrupted");
        }
        return Map.of("status", "no_session");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话管理
    // ═══════════════════════════════════════════════════════════════════

    /** 列出历史会话。 */
    @GetMapping("/sessions")
    public List<SessionListItem> listSessions() {
        var sessions = app.getSessionRegistry().list();
        List<SessionListItem> result = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            var s = sessions.get(i);
            result.add(new SessionListItem(
                    i + 1,
                    s.sessionId(),
                    s.title(),
                    s.messageCount(),
                    s.hasSnapshot(),
                    s.lastActivityAt().toString(),
                    s.summaryPreview()
            ));
        }
        return result;
    }

    /** 恢复会话。 */
    @PostMapping("/sessions/resume")
    public Map<String, Object> resumeSession(@RequestBody ResumeRequest request) {
        try {
            app.switchSession(request.selector());
            return Map.of("status", "ok", "session_id", app.getSession().getSessionId());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /** 新建会话。 */
    @PostMapping("/sessions/new")
    public Map<String, Object> newSession() {
        app.newSession();
        return Map.of("status", "ok", "session_id", app.getSession().getSessionId());
    }

    /** 删除会话。 */
    @DeleteMapping("/sessions/{selector}")
    public Map<String, Object> deleteSession(@PathVariable String selector) {
        app.getSessionRegistry().delete(selector);
        return Map.of("status", "deleted", "selector", selector);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  工具列表
    // ═══════════════════════════════════════════════════════════════════

    @GetMapping("/tools")
    public List<ToolListItem> listTools() {
        var names = app.getToolRegistry().getAllToolNames();
        List<ToolListItem> result = new ArrayList<>();
        for (String name : names) {
            var desc = app.getToolRegistry().get(name);
            result.add(new ToolListItem(
                    name,
                    app.getToolRegistry().getPermission(name).name(),
                    app.getToolRegistry().isMcpTool(name),
                    desc != null ? desc.getDescription() : ""
            ));
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  请求/响应 DTO
    // ═══════════════════════════════════════════════════════════════════

    public record ChatRequest(String message) {}
    public record ResumeRequest(String selector) {}

    public record SessionListItem(
            int index,
            String sessionId,
            String title,
            long messageCount,
            boolean hasSnapshot,
            String lastActivityAt,
            String summaryPreview
    ) {}

    public record ToolListItem(
            String name,
            String permission,
            boolean isMcp,
            String description
    ) {}
}
