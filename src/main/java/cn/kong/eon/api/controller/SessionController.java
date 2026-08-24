package cn.kong.eon.api.controller;

import cn.kong.eon.api.dto.CreateSessionRequest;
import cn.kong.eon.api.dto.SessionResponse;
import cn.kong.eon.service.AgentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理 API。
 * <p>
 * POST   /api/v1/sessions        — 创建新会话
 * GET    /api/v1/sessions        — 列出所有活跃会话
 * GET    /api/v1/sessions/{id}   — 获取会话信息
 * DELETE /api/v1/sessions/{id}   — 关闭会话
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final AgentService agentService;

    public SessionController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        String message = request.getMessage() != null ? request.getMessage() : "";
        String sessionId = agentService.createSession(message);
        return ResponseEntity.ok(buildSessionResponse(sessionId));
    }

    @GetMapping
    public ResponseEntity<List<SessionResponse>> listSessions() {
        return ResponseEntity.ok(agentService.listSessions());
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable("sessionId") String sessionId) {
        var session = agentService.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SessionResponse(
                session.getSessionId(),
                session.getCreatedAt(),
                session.getLastActiveAt(),
                session.getState().getTurnCount()
        ));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> closeSession(@PathVariable("sessionId") String sessionId) {
        agentService.closeSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    private SessionResponse buildSessionResponse(String sessionId) {
        var session = agentService.getSession(sessionId);
        if (session == null) {
            return new SessionResponse(sessionId, null, null, 0);
        }
        return new SessionResponse(
                session.getSessionId(),
                session.getCreatedAt(),
                session.getLastActiveAt(),
                session.getState().getTurnCount()
        );
    }
}
