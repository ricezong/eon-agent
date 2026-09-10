package cn.kong.eon.agent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLF4J 日志监听器。将事件转为可读日志输出，保持后端观测能力。
 * SSE 推送到前端后仍可保留此监听器做后端观测。
 * 使用访问者模式分发事件类型。
 */
public class Slf4jTurnListener implements TurnListener {
    private static final Logger log = LoggerFactory.getLogger(Slf4jTurnListener.class);

    @Override
    public void onEvent(TurnEvent event) {
        event.accept(new Slf4jEventLogger());
    }

    /** SLF4J 日志 Visitor。 */
    private static class Slf4jEventLogger implements TurnEventVisitor<Void> {

        @Override
        public Void visitDelta(AgentDelta e) {
            log.debug("│ agent.delta: kind={}, delta={}", e.kind(),
                    e.delta() != null ? clip(e.delta(), 80) : e.inputDelta() != null ? clip(e.inputDelta(), 80) : "");
            return null;
        }

        @Override
        public Void visitThinking(AgentThinking e) {
            log.info("│ agent.thinking: {}", clip(e.content(), 500));
            return null;
        }

        @Override
        public Void visitMessage(AgentMessage e) {
            String text = e.content().isEmpty() ? "" : e.content().get(0).text();
            log.info("│ agent.message: {}", clip(text, 500));
            return null;
        }

        @Override
        public Void visitToolUse(AgentToolUse e) {
            log.info("│ agent.tool_use: {} perm={} input={}", e.name(), e.evaluatedPermission(),
                    clip(e.input(), 80));
            return null;
        }

        @Override
        public Void visitToolResult(AgentToolResult e) {
            if (e.success()) {
                log.info("│ agent.tool_result: {} content={}", e.name(), clip(e.content(), 500));
            } else {
                log.warn("│ agent.tool_result: {} 失败: {}", e.name(), clip(e.content(), 200));
            }
            return null;
        }

        @Override
        public Void visitUsage(SessionUsage e) {
            log.info("│ session.usage: turn={} prompt={} completion={} total={}",
                    e.turnId(), e.promptTokens(), e.completionTokens(), e.totalTokens());
            return null;
        }

        @Override
        public Void visitStatus(SessionStatus e) {
            log.info("│ session.status: {}{}", e.status(),
                    e.stopReason() != null ? " (" + e.stopReason() + ")" : "");
            return null;
        }

        @Override
        public Void visitError(SessionError e) {
            log.error("│ session.error: {} ({})", e.message(), e.type());
            return null;
        }

        @Override
        public Void visitUnknown(TurnEvent e) {
            log.debug("│ unknown event: {}", e.type());
            return null;
        }

        private static String clip(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }
}
