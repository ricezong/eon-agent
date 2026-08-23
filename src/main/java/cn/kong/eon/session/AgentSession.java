package cn.kong.eon.session;

import cn.kong.eon.agent.EonAgent;
import cn.kong.eon.mcp.McpClientManager;
import cn.kong.eon.model.SessionState;
import cn.kong.eon.tool.InteractionCallback;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话级上下文容器。
 * <p>
 * 持有一次会话所需的全部可变状态：
 * <ul>
 *   <li>{@link EonAgent} — Agent 引擎实例（含会话级 Store、Hook、ToolContext）</li>
 *   <li>{@link SessionState} — 会话对话状态（消息历史、turn 计数等）</li>
 *   <li>{@link McpClientManager} 列表 — MCP 连接，需在会话销毁时关闭</li>
 *   <li>{@link ReentrantLock} — 会话级串行锁，防止同一会话并发 run 导致状态竞争</li>
 *   <li>{@link PendingInteraction} — 异步交互暂停状态</li>
 * </ul>
 */
public class AgentSession {
    private final String sessionId;
    private final EonAgent agent;
    private final SessionState state;
    private final List<McpClientManager> mcpManagers;
    private final ReentrantLock lock = new ReentrantLock();
    private final PendingInteraction pendingInteraction = new PendingInteraction();

    private final Instant createdAt;
    private volatile Instant lastActiveAt;

    public AgentSession(String sessionId, EonAgent agent, SessionState state,
                        List<McpClientManager> mcpManagers) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.state = state;
        this.mcpManagers = mcpManagers;
        this.createdAt = Instant.now();
        this.lastActiveAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public EonAgent getAgent() { return agent; }
    public SessionState getState() { return state; }
    public ReentrantLock getLock() { return lock; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public PendingInteraction getPendingInteraction() { return pendingInteraction; }

    /** 更新最后活跃时间。 */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }

    /**
     * 尝试获取会话锁（非阻塞）。
     * <p>
     * 成功返回 true，调用方在完成后必须调用 {@link #releaseLock()} 释放。
     * 失败返回 false，表示会话已被其他请求占用。
     *
     * @return true 表示成功获取锁
     */
    public boolean acquireLock() {
        return lock.tryLock();
    }

    /**
     * 释放会话锁。
     * <p>
     * 仅在当前线程持有锁时释放，避免 IllegalMonitorStateException。
     */
    public void releaseLock() {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /** 会话锁是否被占用。 */
    public boolean isBusy() {
        return lock.isLocked();
    }

    /** 是否有待处理的用户交互。 */
    public boolean isInteractionPending() {
        return pendingInteraction.isPending();
    }

    /**
     * 提交用户答案，唤醒被阻塞的 Agent 线程。
     *
     * @param answers 用户答案映射
     */
    public void submitInteractionAnswer(Map<String, String> answers) {
        pendingInteraction.submitAnswer(answers);
    }

    /**
     * 创建此会话的 InteractionCallback 实例。
     * <p>
     * 回调在 {@link InteractionCallback#askQuestions} 中：
     * <ol>
     *   <li>将问题暂存到 {@link PendingInteraction}（进入 PENDING 状态）</li>
     *   <li>阻塞当前 Agent 线程，等待用户通过 HTTP 端点提交答案</li>
     *   <li>答案到达后恢复执行，返回用户答案</li>
     * </ol>
     * <p>
     * Agent 线程在 {@link PendingInteraction#awaitAnswer()} 中阻塞（CompletableFuture.get），
     * 用户通过 {@code POST /api/v1/chat/{sessionId}/answer} 提交答案后唤醒。
     * awaitAnswer 返回后自动 reset，支持下次交互。
     */
    public InteractionCallback getInteractionCallback() {
        return new InteractionCallback() {
            @Override
            public Map<String, String> askQuestions(List<Map<String, Object>> questions, String title) {
                // 1. 暂存问题，进入 PENDING 状态（客户端可查询）
                pendingInteraction.setPending(sessionId, questions, title);
                // 2. 阻塞等待用户答案（CompletableFuture.get），答案到达后自动 reset
                Map<String, String> answers = pendingInteraction.awaitAnswer();
                // 3. 返回答案给 AskQuestionTool，Agent 继续执行
                return answers;
            }
        };
    }

    /** 释放会话资源：关闭 Agent 线程池 + MCP 连接。 */
    public void destroy() {
        agent.shutdown();
        for (McpClientManager mgr : mcpManagers) {
            try {
                mgr.close();
            } catch (Exception ignored) {
            }
        }
    }
}
