package cn.kong.eon.web.service;

import cn.kong.eon.web.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话编排服务。
 */
public interface ChatService {

    /**
     * 流式对话，返回 SseEmitter，引擎事件实时推送前端。
     *
     * @throws cn.kong.eon.web.exception.SessionNotFoundException 会话不存在
     * @throws cn.kong.eon.web.exception.SessionBusyException     会话正在执行
     */
    SseEmitter chat(ChatRequest request);

    /** 请求中断指定会话的当前任务。 */
    boolean interrupt(String sessionId);
}
