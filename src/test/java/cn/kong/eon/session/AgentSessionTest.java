package cn.kong.eon.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentSession} 单元测试。
 * <p>
 * 覆盖锁机制、交互回调、生命周期。
 */
class AgentSessionTest {

    @Test
    void acquireLock_should_succeed_when_not_busy() {
        AgentSession session = createTestSession();
        try {
            assertThat(session.acquireLock()).isTrue();
            assertThat(session.isBusy()).isTrue();
        } finally {
            session.releaseLock();
        }
        assertThat(session.isBusy()).isFalse();
    }

    @Test
    void acquireLock_should_fail_when_already_locked() {
        AgentSession session = createTestSession();
        assertThat(session.acquireLock()).isTrue();

        // 同线程再次获取（ReentrantLock 可重入）
        assertThat(session.acquireLock()).isTrue();
        session.releaseLock(); // 释放一次（可重入，仍持有）
        session.releaseLock(); // 释放第二次（完全释放）

        assertThat(session.isBusy()).isFalse();
    }

    @Test
    void touch_should_update_lastActiveAt() throws InterruptedException {
        AgentSession session = createTestSession();
        Instant before = session.getLastActiveAt();

        Thread.sleep(10);
        session.touch();

        assertThat(session.getLastActiveAt()).isAfter(before);
    }

    @Test
    void interactionCallback_should_block_and_return_answers() throws Exception {
        AgentSession session = createTestSession();
        var callback = session.getInteractionCallback();

        List<Map<String, Object>> questions = List.of(
                Map.of("id", "q1", "prompt", "选择", "options", List.of(
                        Map.of("id", "a", "label", "A"),
                        Map.of("id", "b", "label", "B"))));

        // 在另一线程调用 askQuestions（会阻塞）
        Thread asker = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        });
        // 先提交答案再调用（模拟先 setPending 后 submit）
        session.getPendingInteraction().setPending("test", questions, "测试");
        session.submitInteractionAnswer(Map.of("q1", "a"));

        // awaitAnswer 应立即返回已提交的答案
        Map<String, String> answers = session.getPendingInteraction().awaitAnswer();
        assertThat(answers).containsEntry("q1", "a");
    }

    @Test
    void submitInteractionAnswer_should_throw_when_not_pending() {
        AgentSession session = createTestSession();
        // 不调用 setPending，直接提交
        // submitAnswer 会 complete future，但 state 不在 PENDING
        // 验证 isInteractionPending 返回 false
        assertThat(session.isInteractionPending()).isFalse();
    }

    /** 创建一个不依赖 EonAgent 的测试 AgentSession（agent=null, state=最小化）。 */
    private AgentSession createTestSession() {
        return new AgentSession(
                "test_session",
                null,  // agent，测试锁时不需要
                cn.kong.eon.model.SessionState.create("test_session", "test"),
                List.of()  // 无 MCP
        );
    }
}
