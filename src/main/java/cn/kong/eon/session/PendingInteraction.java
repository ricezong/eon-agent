package cn.kong.eon.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话级暂停交互状态。
 * <p>
 * 当 Agent 执行过程中触发 {@code AskQuestionTool} 时，
 * 将问题信息暂存到此处，并阻塞等待用户答案。
 * <p>
 * 生命周期：
 * <pre>
 * Agent 运行 → AskQuestion 触发 → PENDING（暂存问题，阻塞线程）
 *   → 客户端提交答案 → ANSWERED（唤醒阻塞线程，Agent 恢复执行）
 *   → Agent 继续 → IDLE
 * </pre>
 */
public class PendingInteraction {
    private static final Logger log = LoggerFactory.getLogger(PendingInteraction.class);

    private volatile String sessionId;
    private volatile List<Map<String, Object>> questions;
    private volatile String title;
    private volatile Instant createdAt;
    private volatile State state = State.IDLE;

    /** 用于阻塞 Agent 线程，等待用户提交答案。每次 reset 时重新创建。 */
    private volatile CompletableFuture<Map<String, String>> answerFuture = new CompletableFuture<>();

    /** 等待用户答案的超时时间（分钟）。超时后 Agent 自动恢复，视为用户放弃作答。 */
    private static final long AWAIT_TIMEOUT_MINUTES = 10;

    public enum State {
        IDLE,
        PENDING,        // 问题已暂存，等待用户答案
        ANSWERED        // 用户已提交答案，Agent 正在恢复
    }

    /** 暂存问题，进入 PENDING 状态。 */
    public void setPending(String sessionId, List<Map<String, Object>> questions, String title) {
        this.sessionId = sessionId;
        this.questions = questions;
        this.title = title;
        this.createdAt = Instant.now();
        this.state = State.PENDING;
    }

    /** 用户提交答案，唤醒阻塞线程。 */
    public void submitAnswer(Map<String, String> answers) {
        this.state = State.ANSWERED;
        answerFuture.complete(answers);
    }

    /**
     * 阻塞等待用户答案，超时 10 分钟。答案到达后自动 reset，支持下次交互。
     * <p>
     * 超时后返回空 Map，Agent 将以空答案继续执行（视为用户放弃作答）。
     *
     * @return 用户答案映射，超时或异常时返回空 Map
     */
    public Map<String, String> awaitAnswer() {
        try {
            Map<String, String> result = answerFuture.get(AWAIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            // Agent 线程获取到答案后自行 reset，避免 HTTP 线程竞态
            reset();
            return result;
        } catch (TimeoutException e) {
            log.warn("Interaction await timed out after {}min, session={}", AWAIT_TIMEOUT_MINUTES, sessionId);
            reset();
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interaction await interrupted, session={}", sessionId);
            reset();
            return Map.of();
        } catch (ExecutionException e) {
            log.error("Interaction await failed, session={}", sessionId, e);
            reset();
            return Map.of();
        }
    }

    /** 重置为 IDLE 状态（Agent 恢复后调用），重新创建 future 以支持下次交互。 */
    public void reset() {
        this.sessionId = null;
        this.questions = null;
        this.title = null;
        this.createdAt = null;
        this.state = State.IDLE;
        this.answerFuture = new CompletableFuture<>();
    }

    /** 是否有待处理的交互。 */
    public boolean isPending() {
        return state == State.PENDING;
    }

    public String getSessionId() { return sessionId; }
    public List<Map<String, Object>> getQuestions() { return questions; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public State getState() { return state; }
}
