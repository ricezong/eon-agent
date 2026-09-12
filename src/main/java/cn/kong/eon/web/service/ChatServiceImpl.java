package cn.kong.eon.web.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.AgentEngine;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.runtime.cache.SessionRegistry;
import cn.kong.eon.runtime.cache.SessionScope;
import cn.kong.eon.runtime.TaskScope;
import cn.kong.eon.store.index.SessionIndexStore;
import cn.kong.eon.store.index.SessionMeta;
import cn.kong.eon.web.dto.ChatRequest;
import cn.kong.eon.web.exception.SessionBusyException;
import cn.kong.eon.web.exception.SessionNotFoundException;
import cn.kong.eon.web.sse.EventFormatter;
import cn.kong.eon.web.sse.SseEventListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 对话编排服务。创建 SseEmitter，异步执行引擎任务，事件实时推送前端。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private final AgentConfig config;
    private final SessionIndexStore sessionIndexStore;
    private final SessionRegistry registry;
    private final AgentEngine agent;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;
    private final EventFormatter formatter;

    public ChatServiceImpl(AgentConfig config,
                           SessionIndexStore sessionIndexStore,
                           SessionRegistry registry,
                           AgentEngine agent,
                           @Qualifier("sseExecutor") ExecutorService sseExecutor,
                           ObjectMapper objectMapper,
                           EventFormatter formatter) {
        this.config = config;
        this.sessionIndexStore = sessionIndexStore;
        this.registry = registry;
        this.agent = agent;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
    }

    @Override
    public SseEmitter chat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.execute(() -> {
            try {
                runChat(request, emitter);
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

    /** 校验输入，按新旧会话分别处理后执行引擎任务。 */
    private void runChat(ChatRequest request, SseEmitter emitter) {
        String userInput = request.message();
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("输入不能为空。");
        }

        String requestedId = request.sessionId();
        String userId = request.userId() != null ? request.userId() : "default";
        boolean isNew = requestedId == null || requestedId.isBlank();

        String sessionId;
        SessionMeta meta;
        if (isNew) {
            sessionId = createSession(userId, userInput);
            meta = null;
        } else {
            sessionId = resolveExistingSession(userId, requestedId);
            meta = sessionIndexStore.find(userId, requestedId).orElse(null);
        }

        executeTask(sessionId, userInput, meta, emitter);
    }

    /** 创建新会话：生成 ID 并写入索引。 */
    private String createSession(String userId, String userInput) {
        String sessionId = UUID.randomUUID().toString();
        sessionIndexStore.insert(sessionId, userId, deriveTitle(userInput));
        return sessionId;
    }

    /** 校验会话存在并递增用户消息计数。 */
    private String resolveExistingSession(String userId, String requestedId) {
        sessionIndexStore.find(userId, requestedId)
                .orElseThrow(() -> new SessionNotFoundException(requestedId));
        sessionIndexStore.incrementUserMessageCount(requestedId);
        return requestedId;
    }

    /** 占用会话上下文，调引擎执行任务，结束后释放状态。 */
    private void executeTask(String sessionId, String userInput, SessionMeta meta, SseEmitter emitter) {
        SessionScope scope = registry.acquire(sessionId, meta);

        RunContext ctx = null;
        try {
            TaskScope task = new TaskScope(userInput, config.getLoopDetect());
            ctx = new RunContext(scope, task,
                    List.of(new SseEventListener(emitter, objectMapper, formatter)));

            log.info("=== 会话 {} 任务开始 ===", sessionId);
            log.info("用户输入: {}", userInput.length() > 200 ? userInput.substring(0, 200) + "..." : userInput);

            agent.run(ctx);

            log.info("=== 会话 {} 任务结束, 本次 {} 轮, 会话累计 {} tokens ===",
                    sessionId, task.turnCount(), scope.usageAccum().getTotalTokens());
        } finally {
            try {
                sessionIndexStore.touch(sessionId, scope.ledger().getMessageCount());
                registry.release(sessionId);
            } finally {
                if (ctx != null) {
                    ctx.close();
                }
            }
        }
    }

    /** 把异常映射为 SSE session.error 的 type 字段，前端按 type 区分。 */
    private static String errorTypeOf(Throwable e) {
        if (e instanceof SessionNotFoundException) return "session_not_found";
        if (e instanceof SessionBusyException) return "session_busy";
        if (e instanceof IllegalArgumentException) return "bad_request";
        return "runtime_error";
    }

    @Override
    public boolean interrupt(String sessionId) {
        return registry.find(sessionId)
                .map(scope -> {
                    RunContext current = scope.currentRun();
                    if (current == null) {
                        return false;
                    }
                    current.task().requestInterrupt();
                    return true;
                })
                .orElse(false);
    }

    /** 从用户输入派生会话标题（前 10 字符）。 */
    private static String deriveTitle(String userInput) {
        if (userInput == null) return "新会话";
        return userInput.length() <= 10 ? userInput : userInput.substring(0, 10);
    }
}
