package cn.kong.eon.web.service;

import cn.kong.eon.web.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话编排服务。负责会话身份解析（新建/续接）与引擎执行。 */
public interface ChatService {

    /**
     * 流式对话。sessionId 为空时自动创建新会话，否则续接已有会话
     */
    SseEmitter chat(ChatRequest request, String userId);

    boolean interrupt(String sessionId);
}
