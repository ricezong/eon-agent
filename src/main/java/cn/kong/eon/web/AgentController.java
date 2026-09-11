package cn.kong.eon.web;

import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.dto.InterruptRequest;
import cn.kong.eon.web.dto.RunResult;
import cn.kong.eon.web.dto.SessionListItem;
import cn.kong.eon.web.exception.SessionBusyException;
import cn.kong.eon.web.exception.SessionNotFoundException;
import cn.kong.eon.web.service.AgentChatService;
import cn.kong.eon.web.service.AgentSessionService;
import cn.kong.eon.web.sse.SseAgentEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent HTTP/SSE 控制器。<b>只做传输层</b>：参数校验、SseEmitter 生命周期、响应封装。
 * 会话查找、标题派生、账本回放与格式化已全部下沉到 Service。
 * <p>
 * SSE 事件流：engine.delta → engine.thinking → engine.message → engine.tool_use →
 * engine.tool_result → session.usage → session.status
 * <p>
 * 错误语义（契约不变）：{@code /api/chat} 在异步线程内执行，异常走不到
 * {@link GlobalExceptionHandler}，因此一律就地转为 {@code session.error} 事件并带 {@code type}；
 * 非流式接口才由 {@link GlobalExceptionHandler} 转成 HTTP 状态码。
 */
@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentChatService chatService;
    private final AgentSessionService sessionService;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentController(AgentChatService chatService,
                           AgentSessionService sessionService,
                           @Qualifier("sseExecutor") ExecutorService sseExecutor,
                           ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对话
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 发送消息并流式接收 Agent 响应（唯一对话入口）。
     * sessionId 为空时自动创建新会话，非空时恢复已有会话。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.execute(() -> {
            try {
                RunResult result = chatService.chat(request,
                        List.of(new SseAgentEventListener(emitter, objectMapper)));
                emitter.send(SseEmitter.event().name("engine.message.final")
                        .data(Map.of("content", result.content(), "session_id", result.sessionId())));
            } catch (Exception e) {
                log.error("SSE 对话失败", e);
                try {
                    emitter.send(SseEmitter.event().name("session.error")
                            .data(Map.of("message", e.getMessage(), "type", errorTypeOf(e))));
                } catch (Exception ignored) {
                }
            } finally {
                emitter.complete();
            }
        });

        return emitter;
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

    /** 删除会话（硬删除：SQLite 记录 + 会话目录）。 */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "default") String userId) {
        boolean deleted = sessionService.deleteSession(userId, sessionId);
        return Map.of("status", deleted ? "deleted" : "not_found", "session_id", sessionId);
    }

    /** 恢复会话：回放账本，返回与实时 SSE 结构一致的事件列表。 */
    @GetMapping("/sessions/{sessionId}")
    public List<Map<String, Object>> getSession(@PathVariable String sessionId) {
        return sessionService.getSessionEvents(sessionId);
    }

    // ═══════════════════════════════════════════════════════════════════

    /** 把异常映射为 SSE session.error 的 type 字段，前端按 type 区分。 */
    private static String errorTypeOf(Throwable e) {
        if (e instanceof SessionNotFoundException) return "session_not_found";
        if (e instanceof SessionBusyException) return "session_busy";
        return "runtime_error";
    }
}
