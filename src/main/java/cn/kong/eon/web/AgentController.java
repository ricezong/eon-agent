package cn.kong.eon.web;

import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.dto.InterruptRequest;
import cn.kong.eon.web.dto.SessionListItem;
import cn.kong.eon.web.service.AgentChatService;
import cn.kong.eon.web.service.AgentSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** Agent HTTP/SSE 控制器，纯传输层。 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentChatService chatService;
    private final AgentSessionService sessionService;

    @Autowired
    public AgentController(AgentChatService chatService,
                           AgentSessionService sessionService) {
        this.chatService = chatService;
        this.sessionService = sessionService;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对话
    // ═══════════════════════════════════════════════════════════════════

    /** 流式对话，sessionId 为空时自动创建新会话。 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /** 中断指定会话的当前任务。 */
    @PostMapping("/interrupt")
    public Map<String, Object> interrupt(@RequestBody InterruptRequest request) {
        boolean interrupted = chatService.interrupt(request.sessionId());
        return Map.of("status", interrupted ? "interrupted" : "no_session");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  会话管理
    // ═══════════════════════════════════════════════════════════════════

    /** 列出历史会话。 */
    @GetMapping("/sessions")
    public List<SessionListItem> listSessions(
            @RequestParam(required = false, defaultValue = "default") String userId) {
        return sessionService.listSessions(userId);
    }

    /** 删除会话。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "default") String userId) {
        boolean deleted = sessionService.deleteSession(userId, sessionId);
        return Map.of("status", deleted ? "deleted" : "not_found", "session_id", sessionId);
    }

    /** 恢复会话，回放账本事件。 */
    @GetMapping("/sessions/{sessionId}")
    public List<Map<String, Object>> getSession(@PathVariable String sessionId) {
        return sessionService.getSessionEvents(sessionId);
    }
}
