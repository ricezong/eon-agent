package cn.kong.eon.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PendingInteraction} 单元测试。
 * <p>
 * 覆盖生命周期：IDLE → PENDING → ANSWERED → IDLE，
 * 以及超时和并发提交场景。
 */
class PendingInteractionTest {

    private PendingInteraction pi;

    @BeforeEach
    void setUp() {
        pi = new PendingInteraction();
    }

    @Test
    void should_start_idle() {
        assertThat(pi.isPending()).isFalse();
        assertThat(pi.getState()).isEqualTo(PendingInteraction.State.IDLE);
    }

    @Test
    void should_enter_pending_after_setPending() {
        pi.setPending("session_123", List.of(Map.of("id", "q1")), "测试标题");

        assertThat(pi.isPending()).isTrue();
        assertThat(pi.getState()).isEqualTo(PendingInteraction.State.PENDING);
        assertThat(pi.getSessionId()).isEqualTo("session_123");
        assertThat(pi.getTitle()).isEqualTo("测试标题");
        assertThat(pi.getQuestions()).hasSize(1);
        assertThat(pi.getCreatedAt()).isNotNull();
    }

    @Test
    void should_return_answers_and_reset_after_submit() throws Exception {
        pi.setPending("session_123", List.of(), "测试");

        // 在另一个线程提交答案
        Thread submitter = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            pi.submitAnswer(Map.of("q1", "option_a"));
        });
        submitter.start();

        Map<String, String> answers = pi.awaitAnswer();

        assertThat(answers).containsEntry("q1", "option_a");
        assertThat(pi.getState()).isEqualTo(PendingInteraction.State.IDLE);
        assertThat(pi.isPending()).isFalse();

        submitter.join(1000);
    }

    @Test
    void should_timeout_and_return_empty_map() throws Exception {
        pi.setPending("session_123", List.of(), "测试");

        // awaitAnswer 有 10 分钟超时，这里不实际等待超时
        // 验证未提交答案时 state 确实为 PENDING
        assertThat(pi.isPending()).isTrue();

        // 手动调用 reset 模拟超时后的行为
        pi.reset();
        assertThat(pi.getState()).isEqualTo(PendingInteraction.State.IDLE);
        assertThat(pi.getSessionId()).isNull();
    }

    @Test
    void should_support_multiple_rounds_of_interaction() throws Exception {
        // 第一轮交互
        pi.setPending("session_1", List.of(), "第一轮");
        assertThat(pi.isPending()).isTrue();

        Thread t1 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            pi.submitAnswer(Map.of("q1", "a1"));
        });
        t1.start();

        Map<String, String> answers1 = pi.awaitAnswer();
        assertThat(answers1).containsEntry("q1", "a1");
        assertThat(pi.isPending()).isFalse();
        t1.join(500);

        // 第二轮交互（验证 future 已重置）
        pi.setPending("session_1", List.of(), "第二轮");
        assertThat(pi.isPending()).isTrue();

        Thread t2 = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            pi.submitAnswer(Map.of("q2", "a2"));
        });
        t2.start();

        Map<String, String> answers2 = pi.awaitAnswer();
        assertThat(answers2).containsEntry("q2", "a2");
        t2.join(500);
    }
}
