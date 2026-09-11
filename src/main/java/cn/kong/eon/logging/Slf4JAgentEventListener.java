package cn.kong.eon.logging;

import cn.kong.eon.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLF4J 日志监听器。将事件转为可读日志输出。
 */
public class Slf4JAgentEventListener implements AgentEventListener {
    private static final Logger log = LoggerFactory.getLogger(Slf4JAgentEventListener.class);

    @Override
    public void onEvent(AgentEvent event) {
        event.accept(new Slf4jEventLogger());
    }

    /** 日志格式化 Visitor。 */
    private static class Slf4jEventLogger implements AgentEventVisitor<Void> {

        @Override
        public Void visitDelta(AgentDelta e) {
            log.debug("│ agent.delta: kind={}, delta={}", e.kind(), e.delta() != null ? clip(e.delta(), 80) : "");
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
            log.info("│ agent.tool_use: {} input={}", e.name(), clip(e.input(), 80));
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
        public Void visitUnknown(AgentEvent e) {
            log.debug("│ unknown event: {}", e.type());
            return null;
        }

        private static String clip(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }
}
