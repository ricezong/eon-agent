package cn.kong.eon.agent.turn;

import cn.kong.eon.agent.turn.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SLF4J 日志监听器。将事件转为可读日志输出，保持与原日志体系等效的观测能力。
 * 前端接入 SSE/WebSocket 后可移除此监听器或保留做后端观测。
 */
public class Slf4jTurnListener implements TurnListener {
    private static final Logger log = LoggerFactory.getLogger(Slf4jTurnListener.class);

    @Override
    public void onEvent(TurnEvent event) {
        if (event instanceof MessageStarted e) {
            log.info("┌─ 会话开始 │ {} │ 用户: {}",
                    e.sessionId(),
                    e.userInput().length() > 200 ? e.userInput().substring(0, 200) + "..." : e.userInput());

        } else if (event instanceof TextChunk e) {
            log.info("│ 助手回复: {}", e.text().length() > 500
                    ? e.text().substring(0, 500) + "..." : e.text());

        } else if (event instanceof ToolCallStarted e) {
            log.info("│ Turn {} 调用工具: {} 参数={}", e.turn(), e.toolName(), e.args());

        } else if (event instanceof ToolCallCompleted e) {
            if (e.success()) {
                log.info("│ 工具完成: {} 输出: {}", e.toolName(), e.output().substring(0, Math.min(200, e.output().length())));
            } else {
                log.warn("│ 工具失败: {} 输出: {}", e.toolName(), e.output().substring(0, Math.min(200, e.output().length())));
            }

        } else if (event instanceof TaskCompleted e) {
            log.info("└─ 任务完成 │ turns={} │ tokens={}", e.turns(), e.totalTokens());

        } else if (event instanceof TaskStopped e) {
            log.warn("└─ 任务终止: {} │ 原因: {} │ turns={} │ tokens={}",
                    e.category(), e.reason(), e.turns(), e.totalTokens());
        }
    }
}
