package cn.kong.eon.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 向用户提问事件。由 ask_question 工具在阻塞等待用户回答前发出，
 * 本轮 run 不会结束——答案经 /api/answer 投递后作为工具结果继续本轮。
 * questions 直接透传工具入参：每项含 id / prompt / options[{id,label}] / allow_multiple。
 */
public record AgentQuestion(
        String turnId,
        String title,
        List<Map<String, Object>> questions,
        Instant timestamp
) implements AgentEvent {

    @Override
    public String type() {
        return "session.question";
    }

    @Override
    public <T> T accept(AgentEventVisitor<T> visitor) {
        return visitor.visitQuestion(this);
    }

    public static AgentQuestion now(String turnId, String title, List<Map<String, Object>> questions) {
        return new AgentQuestion(turnId, title, questions, Instant.now());
    }
}
