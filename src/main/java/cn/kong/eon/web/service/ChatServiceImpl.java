package cn.kong.eon.web.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.AgentEngine;
import cn.kong.eon.event.SessionStart;
import cn.kong.eon.runtime.RunContext;
import cn.kong.eon.runtime.TaskScope;
import cn.kong.eon.runtime.cache.SessionRegistry;
import cn.kong.eon.runtime.cache.SessionScope;
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
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** 对话编排服务。会话身份解析（新建/续接）与引擎执行。 */
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
    public SseEmitter chat(ChatRequest request, String userId) {
        // 同步阶段：解析会话身份（新建或续接），确保校验失败时不留脏数据
        String requestedId = request.sessionId();
        String sessionId;
        boolean created;
        String sessionTitle;

        if (!StringUtils.hasText(requestedId)) {
            sessionId = UUID.randomUUID().toString();
            sessionTitle = deriveTitle(request.message());
            sessionIndexStore.insert(sessionId, userId, sessionTitle);
            created = true;
            log.info("新建会话 {} (userId={})", sessionId, userId);
        } else {
            SessionMeta meta = sessionIndexStore.find(userId, requestedId)
                    .orElseThrow(() -> new SessionNotFoundException(requestedId));
            // 取索引中的完整 ID：直接用 requestedId 会让前缀成为会话身份，导致上下文串行
            sessionId = meta.sessionId();
            sessionTitle = meta.title();
            sessionIndexStore.incrementUserMessageCount(sessionId);
            created = false;
        }

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        sseExecutor.execute(() -> {
            try {
                execute(sessionId, created, sessionTitle, request.message(), emitter);
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

    /** 占用会话上下文，发出会话身份首帧，调引擎执行任务，结束后释放状态。 */
    private void execute(String sessionId, boolean created, String title, String userInput, SseEmitter emitter) {
        SessionScope scope = registry.acquire(sessionId, !created);

        RunContext ctx = null;
        try {
            TaskScope task = new TaskScope(userInput, config.getLoopDetect());
            ctx = new RunContext(scope, task,
                    List.of(new SseEventListener(emitter, objectMapper, formatter, sessionId)));

            // 首帧：把会话身份交付客户端
            ctx.emit(SessionStart.now(sessionId, title));

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

    /** 从用户输入派生会话标题（前 10 字符）。 */
    private static String deriveTitle(String seed) {
        return seed.length() <= 10 ? seed : seed.substring(0, 10);
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
}
