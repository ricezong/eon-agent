package cn.kong.eon.service;

import cn.kong.eon.agent.TurnCallback;
import cn.kong.eon.api.dto.InteractionResponse;
import cn.kong.eon.api.exception.SessionBusyException;
import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.llm.LlmClient;
import cn.kong.eon.session.AgentBootstrapFactory;
import cn.kong.eon.session.AgentSession;
import cn.kong.eon.session.PendingInteraction;
import cn.kong.eon.session.SessionManager;
import cn.kong.eon.util.JsonMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 核心服务层。
 * <p>
 * 职责：
 * <ul>
 *   <li>创建新会话（委托 {@link AgentBootstrapFactory}）</li>
 *   <li>在指定会话中执行 Agent 主循环（同步 + 异步 + SSE 流式）</li>
 *   <li>通过 {@link SessionManager} 管理会话生命周期</li>
 * </ul>
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final SessionManager sessionManager;
    private final AgentBootstrapFactory factory;
    private final ObjectMapper objectMapper = JsonMapper.get();

    public AgentService(AgentConfig config, LlmClient llmClient, SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.factory = new AgentBootstrapFactory(config, llmClient);
    }

    /**
     * 创建新会话。
     *
     * @param userInput 用户首轮输入
     * @return 会话 ID
     */
    public String createSession(String userInput) {
        String sessionId = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentSession session = factory.createSession(sessionId, userInput);
        sessionManager.put(session);
        return sessionId;
    }

    /**
     * 同步对话。在同一会话中追加用户消息并运行 Agent。
     * <p>
     * 如果会话正在执行其他任务，立即抛出 {@link SessionBusyException}。
     *
     * @param sessionId  会话 ID
     * @param userInput 用户输入
     * @return Agent 最终输出文本
     * @throws SessionBusyException 会话已被占用
     */
    public String chat(String sessionId, String userInput) {
        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        if (!session.acquireLock()) {
            throw new SessionBusyException(sessionId);
        }

        try {
            session.touch();
            session.getState().setUserOriginalInput(userInput);
            String result = session.getAgent().run(session.getState());
            session.touch();
            return result;
        } finally {
            session.releaseLock();
        }
    }

    /**
     * 异步对话。在独立线程池中执行 Agent 主循环。
     *
     * @param job       异步任务上下文
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     */
    @Async("agentExecutor")
    public void chatAsync(ChatJob job, String sessionId, String userInput) {
        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            job.markFailed("会话不存在: " + sessionId);
            return;
        }

        if (!session.acquireLock()) {
            job.markFailed("会话 " + sessionId + " 正在执行中");
            return;
        }

        try {
            job.markRunning();
            session.touch();
            session.getState().setUserOriginalInput(userInput);
            String result = session.getAgent().run(session.getState());
            session.touch();
            job.markCompleted(result, session.getState().getTurnCount());
        } catch (Exception e) {
            log.error("Async chat failed: job={}, session={}", job.getJobId(), sessionId, e);
            job.markFailed(e.getMessage());
        } finally {
            session.releaseLock();
        }
    }

    /**
     * SSE 流式对话。返回 {@link SseEmitter}，在 Agent 执行过程中持续推送事件。
     * <p>
     * Agent 在独立线程中执行（通过 agentExecutor），不阻塞 HTTP 请求线程。
     * 客户端通过 EventSource 实时接收事件。
     *
     * @param sessionId  会话 ID
     * @param userInput 用户输入
     * @return SseEmitter，超时 5 分钟
     * @throws SessionBusyException 会话已被占用
     */
    public SseEmitter chatStream(String sessionId, String userInput) {
        // 5 分钟超时
        SseEmitter emitter = new SseEmitter(300_000L);

        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        if (!session.acquireLock()) {
            throw new SessionBusyException(sessionId);
        }

        TurnCallback callback = new SseEmitterCallback(sessionId, emitter, objectMapper);

        runStreamAsync(session, userInput, callback, emitter);

        return emitter;
    }

    /**
     * 创建新会话并 SSE 流式执行首轮对话。
     *
     * @param userInput 用户输入
     * @return SseEmitter
     */
    public SseEmitter createAndStream(String userInput) {
        String sessionId = createSession(userInput);
        return chatStream(sessionId, userInput);
    }

    @Async("agentExecutor")
    public void runStreamAsync(AgentSession session, String userInput,
                              TurnCallback callback, SseEmitter emitter) {
        try {
            session.touch();
            session.getState().setUserOriginalInput(userInput);
            session.getAgent().runStream(session.getState(), callback);
            session.touch();
        } catch (Exception e) {
            log.error("Stream chat failed: session={}", session.getSessionId(), e);
            try {
                String errorJson = objectMapper.writeValueAsString(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(errorJson));
            } catch (JsonProcessingException jpe) {
                log.error("Failed to serialize error JSON", jpe);
            } catch (Exception ignored) {}
        } finally {
            session.releaseLock();
            emitter.complete();
        }
    }

    /** 获取会话信息。 */
    public AgentSession getSession(String sessionId) {
        return sessionManager.get(sessionId);
    }

    /** 关闭会话。 */
    public void closeSession(String sessionId) {
        sessionManager.remove(sessionId);
    }

    /** 列出所有活跃会话 ID。 */
    public java.util.List<String> listSessions() {
        return sessionManager.listSessionIds();
    }

    /**
     * 获取会话的交互状态。如果存在待处理的交互，返回问题信息。
     */
    public InteractionResponse getInteraction(String sessionId) {
        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        PendingInteraction pi = session.getPendingInteraction();
        if (pi.isPending()) {
            return InteractionResponse.pending(sessionId, pi.getTitle(),
                    pi.getQuestions(), pi.getCreatedAt());
        }
        return InteractionResponse.idle(sessionId);
    }

    /**
     * 提交用户答案，恢复被暂停的 Agent 执行。
     * <p>
     * 如果会话有待处理的交互（AskQuestion），提交答案后：
     * 1. 答案被注入到 AskQuestion 工具的回调
     * 2. Agent 从暂停点恢复执行
     * 3. 返回 Agent 继续执行后的最终输出
     *
     * @param sessionId 会话 ID
     * @param answers   用户答案映射
     * @return Agent 恢复执行后的输出
     */
    public String submitAnswer(String sessionId, Map<String, String> answers) {
        AgentSession session = sessionManager.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        PendingInteraction pi = session.getPendingInteraction();
        if (!pi.isPending()) {
            throw new IllegalStateException("会话没有待处理的交互");
        }

        log.info("Submitting answer for session={}, questions={}", sessionId, answers.size());

        // Agent 线程在 PendingInteraction.awaitAnswer() 中阻塞，收到答案后自行 reset
        session.submitInteractionAnswer(answers);

        return "答案已提交，Agent 正在恢复执行。";
    }

    @PreDestroy
    public void shutdown() {
        sessionManager.destroyAll();
    }
}
